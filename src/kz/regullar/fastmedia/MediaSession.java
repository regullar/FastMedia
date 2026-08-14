package kz.regullar.fastmedia;

import kz.regullar.fastmedia.internal.FastMediaNative;
import java.text.Normalizer;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * High-level entry point to FastMedia. A single session gives you everything:
 * current track, playback position, transport control, a real audio waveform,
 * and lyrics.
 *
 * <pre>{@code
 * try (MediaSession session = MediaSession.open()) {
 *     session.addListener(new MediaListener() {
 *         public void onTrackChanged(NowPlaying np)      { ... }
 *         public void onPositionChanged(PlaybackPosition p) { ... }
 *         public void onPlaybackStopped()                { ... }
 *     });
 *     session.togglePlayPause();
 * }
 * }</pre>
 *
 * <p>The session polls the system on its own daemon threads with adaptive
 * intervals (faster while playing, slower when idle) and pushes changes to
 * {@link MediaListener}s — callers never write polling loops themselves.
 */
public final class MediaSession implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(MediaSession.class.getName());

    // Lightweight progress updates only while actively playing (ms).
    private static final long POSITION_PLAYING_TICK_MS = 500L;
    private static final long HEARTBEAT_FALLBACK_MS = 10_000L;

    private static final long LYRICS_NEGATIVE_TTL_MS = 60_000L;
    private static final int LYRICS_CACHE_LIMIT = 128;

    private final CopyOnWriteArrayList<MediaListener> listeners = new CopyOnWriteArrayList<>();

    private final ScheduledExecutorService monitor;
    private final ExecutorService eventExecutor;
    private final ExecutorService lyricsExecutor;

    // Strong reference to native callback to prevent GC.
    private final FastMediaNative.MediaEventCallback nativeCallback = this::onNativeEvent;

    private final AtomicBoolean metadataBusy = new AtomicBoolean(false);
    private final AtomicBoolean positionBusy = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean waveformStarted = new AtomicBoolean(false);

    private volatile boolean hasMedia = false;
    private volatile boolean playing = false;

    private final ConcurrentHashMap<String, Lyrics> lyricsHits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lyricsMisses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<Lyrics>> lyricsInFlight = new ConcurrentHashMap<>();

    private MediaSession() {
        this.monitor = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "FastMedia-Monitor"));
        this.eventExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "FastMedia-Events"));
        this.lyricsExecutor = Executors.newSingleThreadExecutor(r -> daemon(r, "FastMedia-Lyrics"));
    }

    /** Loads the native library and starts monitoring using WinRT Push Events. */
    public static MediaSession open() {
        NativeBackend.load();
        MediaSession session = new MediaSession();
        session.startMonitoring();
        return session;
    }

    private void startMonitoring() {
        // 1. Register WinRT Push Events callback in native backend
        NativeBackend.registerEventCallback(nativeCallback);

        // 2. Initial state discovery
        eventExecutor.submit(this::refreshMetadata);

        // 3. Smooth progress ticker (runs only when playing) + relaxed safety heartbeat
        monitor.scheduleAtFixedRate(this::smoothPositionTick, POSITION_PLAYING_TICK_MS,
                POSITION_PLAYING_TICK_MS, TimeUnit.MILLISECONDS);
        monitor.scheduleAtFixedRate(this::heartbeatCheck, HEARTBEAT_FALLBACK_MS,
                HEARTBEAT_FALLBACK_MS, TimeUnit.MILLISECONDS);
    }

    // --- WinRT Push Event Handler ---

    private void onNativeEvent(int eventType) {
        if (closed.get()) {
            return;
        }
        eventExecutor.submit(() -> handleNativeEvent(eventType));
    }

    private void handleNativeEvent(int eventType) {
        if (closed.get()) {
            return;
        }
        switch (eventType) {
            case FastMediaNative.EVENT_MEDIA_PROPERTIES_CHANGED:
            case FastMediaNative.EVENT_SESSION_CHANGED:
                refreshMetadata();
                break;
            case FastMediaNative.EVENT_PLAYBACK_INFO_CHANGED:
                refreshPlaybackInfo();
                break;
            case FastMediaNative.EVENT_TIMELINE_PROPERTIES_CHANGED:
                refreshPosition();
                break;
            case FastMediaNative.EVENT_PLAYBACK_STOPPED:
                handlePlaybackStopped();
                break;
            default:
                break;
        }
    }

    private void refreshMetadata() {
        if (closed.get() || !metadataBusy.compareAndSet(false, true)) {
            return;
        }
        try {
            NowPlaying np = NativeBackend.queryNowPlaying();
            if (np == null || np.title().isEmpty()) {
                handlePlaybackStopped();
                return;
            }
            boolean firstAppearance = !hasMedia;
            hasMedia = true;
            playing = np.isPlaying();

            if (np.trackChanged() || firstAppearance) {
                dispatch(l -> l.onTrackChanged(np));
            }
            PlaybackPosition pos = new PlaybackPosition(np.isPlaying(), np.durationMs(), np.positionMs());
            dispatch(l -> l.onPositionChanged(pos));
        } catch (Throwable t) {
            rethrowIfFatal(t);
            LOG.log(Level.FINE, "[FastMedia] metadata refresh failed", t);
        } finally {
            metadataBusy.set(false);
        }
    }

    private void refreshPlaybackInfo() {
        if (closed.get()) {
            return;
        }
        try {
            PlaybackPosition pos = NativeBackend.queryPosition();
            if (pos == null) {
                handlePlaybackStopped();
                return;
            }
            boolean prevPlaying = playing;
            playing = pos.playing();
            hasMedia = true;
            dispatch(l -> l.onPositionChanged(pos));
            if (prevPlaying != playing) {
                refreshMetadata();
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
            LOG.log(Level.FINE, "[FastMedia] playback info refresh failed", t);
        }
    }

    private void refreshPosition() {
        if (closed.get()) {
            return;
        }
        try {
            PlaybackPosition pos = NativeBackend.queryPosition();
            if (pos != null) {
                hasMedia = true;
                playing = pos.playing();
                dispatch(l -> l.onPositionChanged(pos));
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
            LOG.log(Level.FINE, "[FastMedia] position refresh failed", t);
        }
    }

    private void handlePlaybackStopped() {
        if (hasMedia) {
            hasMedia = false;
            playing = false;
            dispatch(MediaListener::onPlaybackStopped);
        }
    }

    private void smoothPositionTick() {
        if (closed.get() || !hasMedia || !playing || !positionBusy.compareAndSet(false, true)) {
            return;
        }
        try {
            PlaybackPosition pos = NativeBackend.queryPosition();
            if (pos != null && pos.playing()) {
                dispatch(l -> l.onPositionChanged(pos));
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
            LOG.log(Level.FINE, "[FastMedia] smooth position tick failed", t);
        } finally {
            positionBusy.set(false);
        }
    }

    private void heartbeatCheck() {
        if (closed.get()) {
            return;
        }
        eventExecutor.submit(this::refreshMetadata);
    }

    // --- One-shot queries (always fresh) ---

    /** Current track, or {@code null} when nothing is playing. */
    public NowPlaying nowPlaying() {
        return NativeBackend.queryNowPlaying();
    }

    /** Current playback position, or {@code null} when unavailable. */
    public PlaybackPosition position() {
        return NativeBackend.queryPosition();
    }

    // --- Transport ---

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
        return NativeBackend.seek(Math.max(0L, position.toMillis()));
    }

    public boolean seek(long positionMs) {
        return NativeBackend.seek(Math.max(0L, positionMs));
    }

    // --- Listeners ---

    public void addListener(MediaListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(MediaListener listener) {
        listeners.remove(listener);
    }

    // --- Waveform (real PCM via WASAPI loopback) ---

    /**
     * Fills {@code out} with the most recent waveform points (normalised peaks
     * in {@code [0, 1]}, oldest first) and returns how many were written.
     * Audio capture is started lazily on the first call.
     */
    public int sampleWaveform(float[] out) {
        if (out == null || out.length == 0) {
            return 0;
        }
        ensureWaveform();
        return NativeBackend.waveform(out);
    }

    /** Smoothed current loudness in {@code [0, 1]}. Starts capture lazily. */
    public float audioLevel() {
        ensureWaveform();
        return NativeBackend.audioLevel();
    }

    /** Stops audio capture. It restarts automatically on the next waveform call. */
    public void stopWaveform() {
        if (waveformStarted.compareAndSet(true, false)) {
            try {
                NativeBackend.stopAudioCapture();
            } catch (RuntimeException e) {
                LOG.log(Level.FINE, "[FastMedia] stopWaveform failed", e);
            }
        }
    }

    private void ensureWaveform() {
        if (waveformStarted.compareAndSet(false, true) && !NativeBackend.startAudioCapture()) {
            waveformStarted.set(false);
        }
    }

    // --- Lyrics ---

    /**
     * Fetches lyrics for a track. Results are cached (positively, and negatively
     * for a minute), and concurrent requests for the same track share one fetch.
     * The future always completes — never exceptionally — yielding
     * {@link Lyrics#notFound()}/{@link Lyrics#unavailable()} when there is nothing.
     */
    public CompletableFuture<Lyrics> requestLyrics(String artist, String title) {
        String key = lyricsKey(artist, title);
        if (key.isEmpty()) {
            return CompletableFuture.completedFuture(Lyrics.notFound());
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
        return lyricsInFlight.computeIfAbsent(key, k ->
                CompletableFuture.supplyAsync(() -> fetchAndCache(k, artist, title), lyricsExecutor));
    }

    /** Returns already-cached lyrics for a track, or {@code null}. Never blocks. */
    public Lyrics cachedLyrics(String artist, String title) {
        String key = lyricsKey(artist, title);
        return key.isEmpty() ? null : lyricsHits.get(key);
    }

    private Lyrics fetchAndCache(String key, String artist, String title) {
        try {
            Lyrics lyrics = NativeBackend.fetchLyrics(artist, title);
            if (lyrics.isPresent()) {
                if (lyricsHits.size() >= LYRICS_CACHE_LIMIT) {
                    lyricsHits.clear();
                }
                lyricsHits.put(key, lyrics);
            } else if (lyrics.available()) {
                lyricsMisses.put(key, System.currentTimeMillis());
            }
            return lyrics;
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "[FastMedia] lyrics fetch failed", e);
            return Lyrics.unavailable();
        } finally {
            lyricsInFlight.remove(key);
        }
    }

    private void dispatch(java.util.function.Consumer<MediaListener> action) {
        for (MediaListener listener : listeners) {
            try {
                action.accept(listener);
            } catch (Throwable t) {
                rethrowIfFatal(t);
                LOG.log(Level.FINE, "[FastMedia] listener threw", t);
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        listeners.clear();
        NativeBackend.unregisterEventCallback();
        monitor.shutdownNow();
        eventExecutor.shutdownNow();
        lyricsExecutor.shutdownNow();
        stopWaveform();
        try {
            NativeBackend.clearCache();
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "[FastMedia] clearCache on close failed", e);
        }
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
        return n.replaceAll("[^\\p{L}\\p{Nd}]+", " ").strip();
    }
}
