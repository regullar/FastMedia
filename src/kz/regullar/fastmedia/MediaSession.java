package kz.regullar.fastmedia;

import kz.regullar.fastmedia.internal.FastMediaNative;
import kz.regullar.fastmedia.internal.LyricsClient;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Threading model: every native query and every state write happens on one worker thread, so
 * the state needs no locks; public getters only read immutable snapshots. Listener callbacks
 * run in order on a separate callback thread, so a slow listener never stalls polling.
 * Several sessions may be open at once: they share the native backend.
 */
public final class MediaSession implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(MediaSession.class.getName());
    private static final long TICK_MS = 500L;
    private static final long NATIVE_RESYNC_NANOS = TimeUnit.MILLISECONDS.toNanos(2_500L);
    private static final long HEARTBEAT_MS = 10_000L;
    private static final long HEARTBEAT_WITHOUT_EVENTS_MS = 1_000L;
    private static final long OPEN_PRIME_TIMEOUT_MS = 3_000L;

    private static final long LYRICS_NEGATIVE_TTL_MS = 60_000L;
    private static final int LYRICS_CACHE_LIMIT = 128;
    private static final int LYRICS_MISSES_LIMIT = 256;
    private static final Pattern NORMALIZE_PATTERN = Pattern.compile("[^\\p{L}\\p{Nd}]+");

    /** Position at a moment in time; extrapolated on read. */
    private record Anchor(boolean playing, long durationMs, long positionMs, long atNanos) {
        PlaybackPosition at(long nowNanos) {
            long elapsedMs = playing ? Math.max(0L, (nowNanos - atNanos) / 1_000_000L) : 0L;
            long position = positionMs + elapsedMs;
            if (durationMs > 0 && position > durationMs) {
                position = durationMs;
            }
            return new PlaybackPosition(playing, durationMs, position);
        }
    }

    private final CopyOnWriteArrayList<MediaListener> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService worker;
    private final ExecutorService callbackExecutor;
    private final ExecutorService lyricsExecutor;
    private final IntConsumer eventSink = this::onNativeEvent;

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean metadataQueued = new AtomicBoolean(false);
    private final Object waveformLock = new Object();
    private volatile boolean waveformAcquired = false;

    // Written only by the worker; null while nothing is playing.
    private volatile NowPlaying current;
    private volatile Anchor anchor;
    private long lastNativeSyncNanos;

    private final ConcurrentHashMap<String, Lyrics> lyricsHits = new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<String, Long> lyricsMisses = new ConcurrentHashMap<>(64);
    private final ConcurrentHashMap<String, CompletableFuture<Lyrics>> lyricsInFlight = new ConcurrentHashMap<>();

    private MediaSession() {
        this.worker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "FastMedia-Worker"));
        this.callbackExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "FastMedia-Callbacks"));
        this.lyricsExecutor = Executors.newFixedThreadPool(4, r -> daemon(r, "FastMedia-Lyrics"));
    }

    /**
     * Opens a session. The current track (if any) is already known when this returns, and
     * {@link #addListener} replays it to every new listener.
     */
    public static MediaSession open() {
        NativeBackend.load();
        MediaSession session = new MediaSession();
        session.start();
        return session;
    }

    private void start() {
        boolean events = NativeBackend.addEventSink(eventSink);
        if (!events) {
            LOG.warning("[FastMedia] SMTC push events unavailable, polling every "
                    + HEARTBEAT_WITHOUT_EVENTS_MS + " ms instead");
        }
        try {
            worker.submit(this::refreshMetadata).get(OPEN_PRIME_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException e) {
            LOG.log(Level.FINE, "[FastMedia] initial refresh did not finish", e);
        }
        long heartbeat = events ? HEARTBEAT_MS : HEARTBEAT_WITHOUT_EVENTS_MS;
        worker.scheduleWithFixedDelay(this::positionTick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(this::refreshMetadata, heartbeat, heartbeat, TimeUnit.MILLISECONDS);
    }

    // ----- Native events (WinRT threads) -----

    private void onNativeEvent(int eventType) {
        if (closed.get()) {
            return;
        }
        switch (eventType) {
            case FastMediaNative.EVENT_MEDIA_PROPERTIES_CHANGED:
            case FastMediaNative.EVENT_SESSION_CHANGED:
                // Players fire these in bursts; one queued refresh covers the whole burst.
                if (metadataQueued.compareAndSet(false, true)) {
                    runOnWorker(() -> {
                        metadataQueued.set(false);
                        refreshMetadata();
                    });
                }
                break;
            case FastMediaNative.EVENT_PLAYBACK_INFO_CHANGED:
                runOnWorker(this::refreshPlaybackInfo);
                break;
            case FastMediaNative.EVENT_TIMELINE_PROPERTIES_CHANGED:
                runOnWorker(this::refreshPosition);
                break;
            case FastMediaNative.EVENT_PLAYBACK_STOPPED:
                runOnWorker(this::handlePlaybackStopped);
                break;
            default:
                break;
        }
    }

    // ----- Worker thread -----

    private void refreshMetadata() {
        if (closed.get()) {
            return;
        }
        NowPlaying np;
        try {
            np = NativeBackend.queryNowPlaying();
        } catch (RuntimeException e) {
            // Includes TransientFailure: keep the last known state rather than flicker to "stopped".
            LOG.log(Level.FINE, "[FastMedia] metadata refresh failed", e);
            return;
        }
        if (np == null || np.title().isEmpty()) {
            handlePlaybackStopped();
            return;
        }
        NowPlaying previous = current;
        // Decided here rather than trusting the native flag: that flag is process-wide and is
        // consumed by whichever session asks first.
        boolean changed = previous == null
                || !np.title().equals(previous.title())
                || !np.artist().equals(previous.artist())
                || !np.source().equals(previous.source())
                || !Arrays.equals(np.albumArt(), previous.albumArt());
        NowPlaying snapshot = new NowPlaying(np.title(), np.artist(), np.source(), np.albumArt(),
                np.isPlaying(), np.durationMs(), np.positionMs(), changed);
        current = snapshot;
        lastNativeSyncNanos = System.nanoTime();
        setAnchor(np.isPlaying(), np.durationMs(), np.positionMs());
        if (changed) {
            dispatch(l -> l.onTrackChanged(snapshot));
        }
        PlaybackPosition pos = anchor.at(System.nanoTime());
        dispatch(l -> l.onPositionChanged(pos));
    }

    private void refreshPlaybackInfo() {
        Anchor before = anchor;
        PlaybackPosition pos = refreshPosition();
        if (pos != null && before != null && before.playing() != pos.playing()) {
            refreshMetadata(); // keeps nowPlaying().isPlaying() in step
        }
    }

    /** @return the fresh position, or null when there is none (stopped, closed or transient) */
    private PlaybackPosition refreshPosition() {
        if (closed.get()) {
            return null;
        }
        PlaybackPosition pos;
        try {
            pos = NativeBackend.queryPosition();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "[FastMedia] position refresh failed", e);
            return null;
        }
        lastNativeSyncNanos = System.nanoTime();
        if (pos == null) {
            handlePlaybackStopped();
            return null;
        }
        if (current == null) {
            // Media appeared without a metadata event: fetch the track as well.
            refreshMetadata();
            return pos;
        }
        setAnchor(pos.playing(), pos.durationMs(), pos.positionMs());
        dispatch(l -> l.onPositionChanged(pos));
        return pos;
    }

    private void positionTick() {
        Anchor a = anchor;
        if (closed.get() || a == null) {
            return;
        }
        long now = System.nanoTime();
        // Resync even while paused: that is how a resume is noticed if an event is missed.
        if (now - lastNativeSyncNanos >= NATIVE_RESYNC_NANOS) {
            refreshPosition();
            return;
        }
        if (a.playing()) {
            PlaybackPosition pos = a.at(now);
            dispatch(l -> l.onPositionChanged(pos));
        }
    }

    private void handlePlaybackStopped() {
        if (current == null && anchor == null) {
            return;
        }
        current = null;
        anchor = null;
        dispatch(MediaListener::onPlaybackStopped);
    }

    private void setAnchor(boolean playing, long durationMs, long positionMs) {
        anchor = new Anchor(playing, durationMs, positionMs, System.nanoTime());
    }

    private void runOnWorker(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }

    // ----- Public API -----

    /**
     * @return the current track as last reported by the system, or null when nothing is playing.
     * Never blocks.
     */
    public NowPlaying nowPlaying() {
        return current;
    }

    /**
     * @return the extrapolated playback position, or null when nothing is playing. Never blocks.
     */
    public PlaybackPosition position() {
        Anchor a = anchor;
        return a == null ? null : a.at(System.nanoTime());
    }

    public boolean play() {
        return NativeBackend.play();
    }

    public boolean pause() {
        return NativeBackend.pause();
    }

    public boolean togglePlayPause() {
        return NativeBackend.toggle();
    }

    public boolean skipNext() {
        return NativeBackend.next();
    }

    public boolean skipPrevious() {
        return NativeBackend.previous();
    }

    public boolean seek(Duration position) {
        return seek(position.toMillis());
    }

    public boolean seek(long positionMs) {
        long target = Math.max(0L, positionMs);
        boolean ok = NativeBackend.seek(target);
        if (ok) {
            // Move the anchor right away instead of drifting from the old position until the
            // player reports the new timeline.
            runOnWorker(() -> {
                Anchor a = anchor;
                if (a != null) {
                    setAnchor(a.playing(), a.durationMs(), target);
                    PlaybackPosition pos = anchor.at(System.nanoTime());
                    dispatch(l -> l.onPositionChanged(pos));
                }
            });
        }
        return ok;
    }

    /**
     * Adds a listener. If a track is already playing it is replayed to this listener at once,
     * so nothing is missed by registering after {@link #open()}.
     */
    public void addListener(MediaListener listener) {
        if (listener == null || !listeners.addIfAbsent(listener)) {
            return;
        }
        NowPlaying np = current;
        if (np == null) {
            return;
        }
        PlaybackPosition pos = position();
        submitCallback(() -> {
            if (!listeners.contains(listener)) {
                return;
            }
            notifyListener(listener, l -> l.onTrackChanged(np));
            if (pos != null) {
                notifyListener(listener, l -> l.onPositionChanged(pos));
            }
        });
    }

    public void removeListener(MediaListener listener) {
        listeners.remove(listener);
    }

    public int sampleWaveform(float[] out) {
        if (out == null || out.length == 0) {
            return 0;
        }
        ensureWaveform();
        return NativeBackend.waveform(out);
    }

    public float audioLevel() {
        ensureWaveform();
        return NativeBackend.audioLevel();
    }

    /**
     * Processes that currently own an audio render session, each usable as a waveform source.
     * Scans every audio endpoint, so call it on demand rather than per frame.
     */
    public List<AudioSource> audioSources() {
        return NativeBackend.listAudioSources();
    }

    /**
     * Captures only {@code pid}'s audio instead of the whole system mix. Windows refuses
     * per-process capture for protected or elevated targets, in which case the capture
     * silently falls back to the system mix. The waveform source is process-wide: it applies
     * to every open session.
     *
     * @return false if no such process is running
     */
    public boolean setWaveformSource(int pid) {
        return NativeBackend.setWaveformSource(Math.max(0, pid));
    }

    /**
     * Follows the app that owns the current media session. This is the default.
     */
    public void setWaveformSourceAuto() {
        NativeBackend.setWaveformSource(0);
    }

    /**
     * @return the PID actually being captured, or 0 when the whole system mix is used
     */
    public int waveformSourcePid() {
        return NativeBackend.waveformSourcePid();
    }

    public void stopWaveform() {
        synchronized (waveformLock) {
            if (waveformAcquired) {
                waveformAcquired = false;
                NativeBackend.releaseWaveform();
            }
        }
    }

    private void ensureWaveform() {
        if (waveformAcquired) {
            return;
        }
        synchronized (waveformLock) {
            if (!waveformAcquired && !closed.get()) {
                waveformAcquired = NativeBackend.acquireWaveform();
            }
        }
    }

    public CompletableFuture<Lyrics> requestLyrics(String artist, String title) {
        String key = lyricsKey(artist, title);
        if (key.isEmpty()) {
            return CompletableFuture.completedFuture(Lyrics.notFound());
        }
        if (closed.get()) {
            return CompletableFuture.completedFuture(Lyrics.unavailable());
        }
        Lyrics hit = lyricsHits.get(key);
        if (hit != null) {
            return CompletableFuture.completedFuture(hit);
        }
        Long missAt = lyricsMisses.get(key);
        if (missAt != null) {
            if (System.currentTimeMillis() - missAt < LYRICS_NEGATIVE_TTL_MS) {
                return CompletableFuture.completedFuture(Lyrics.notFound());
            }
            lyricsMisses.remove(key);
        }
        try {
            return lyricsInFlight.computeIfAbsent(key, k -> {
                CompletableFuture<Lyrics> future = CompletableFuture.supplyAsync(
                        () -> fetchAndCache(k, artist, title), lyricsExecutor);
                future.whenComplete((r, ex) -> lyricsInFlight.remove(k));
                return future;
            });
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(Lyrics.unavailable());
        }
    }

    public Lyrics cachedLyrics(String artist, String title) {
        String key = lyricsKey(artist, title);
        return key.isEmpty() ? null : lyricsHits.get(key);
    }

    private Lyrics fetchAndCache(String key, String artist, String title) {
        try {
            Lyrics lyrics = LyricsClient.fetchLyrics(artist, title);
            if (lyrics.isPresent()) {
                if (lyricsHits.size() >= LYRICS_CACHE_LIMIT) {
                    lyricsHits.keySet().stream().limit(32).forEach(lyricsHits::remove);
                }
                lyricsHits.put(key, lyrics);
            } else if (lyrics.available()) {
                if (lyricsMisses.size() >= LYRICS_MISSES_LIMIT) {
                    lyricsMisses.keySet().stream().limit(64).forEach(lyricsMisses::remove);
                }
                lyricsMisses.put(key, System.currentTimeMillis());
            }
            return lyrics;
        } catch (Exception e) {
            LOG.log(Level.FINE, "[FastMedia] lyrics fetch failed", e);
            return Lyrics.unavailable();
        }
    }

    private void dispatch(Consumer<MediaListener> action) {
        if (closed.get()) {
            return;
        }
        submitCallback(() -> {
            for (MediaListener listener : listeners) {
                notifyListener(listener, action);
            }
        });
    }

    private void submitCallback(Runnable task) {
        try {
            callbackExecutor.execute(task);
        } catch (RejectedExecutionException ignored) {
            // closing
        }
    }

    private static void notifyListener(MediaListener listener, Consumer<MediaListener> action) {
        try {
            action.accept(listener);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            LOG.log(Level.FINE, "[FastMedia] listener threw", t);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        listeners.clear();
        NativeBackend.removeEventSink(eventSink);
        stopWaveform();
        worker.shutdownNow();
        callbackExecutor.shutdownNow();
        lyricsExecutor.shutdownNow();
        lyricsHits.clear();
        lyricsMisses.clear();
        lyricsInFlight.clear();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    }

    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
    }

    private static String lyricsKey(String artist, String title) {
        String t = normalizeKey(title);
        if (t.isEmpty()) {
            return "";
        }
        return normalizeKey(artist) + "|" + t;
    }

    private static String normalizeKey(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String n = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return NORMALIZE_PATTERN.matcher(n).replaceAll(" ").strip();
    }
}
