package kz.regullar.mapplevisuals.ui.overlay;

import kz.regullar.fastmedia.*;
import kz.regullar.mapplevisuals.events.render.EventRender;
import kz.regullar.mapplevisuals.module.impl.render.Interface;
import kz.regullar.mapplevisuals.render.RenderUtils;
import kz.regullar.mapplevisuals.render.RoundedRectBatch;
import kz.regullar.mapplevisuals.render.text.FontLayout;
import kz.regullar.mapplevisuals.render.text.MsdfBatch;
import kz.regullar.mapplevisuals.render.text.MsdfTextRenderer;
import kz.regullar.mapplevisuals.util.animation.AnimationClock;
import kz.regullar.mapplevisuals.util.animation.UiAnimationManager;
import kz.regullar.mapplevisuals.util.color.Material3Colors;
import kz.regullar.mapplevisuals.util.color.Material3Tokens;
import kz.regullar.mapplevisuals.util.color.vibrant.Vibrant;
import kz.regullar.mapplevisuals.util.color.vibrant.VibrantPalette;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class MediaPlayerRenderer {
    private static final Logger LOGGER = Logger.getLogger(MediaPlayerRenderer.class.getName());
    private static final String UNKNOWN_ARTIST = "unknown artist";
    private static final String UNKNOWN_SOURCE = "unknown source";

    private static final Pattern MULTISPACE = Pattern.compile("\\s+");

    private static void rethrowIfFatal(Throwable t) {
        if (t instanceof VirtualMachineError) {
            throw (VirtualMachineError) t;
        }
    }

    private static final long COVER_CROSSFADE_DURATION_NS = 320_000_000L;
    private static final int WRAPPED_LYRICS_CACHE_CAPACITY = 48;

    private static final float BOX_WIDTH = 190f;
    private static final float COMPACT_BOX_HEIGHT = 48f;
    private static final float EXPANDED_BOX_HEIGHT = 124f;
    private static final float BOX_RADIUS = Material3Tokens.MEDIA_RADIUS;
    private static final float HEADER_HEIGHT = COMPACT_BOX_HEIGHT;
    private static final float CONTENT_PADDING = Material3Tokens.MEDIA_PADDING;
    private static final float COVER_SIZE = 32f;
    private static final float COVER_RADIUS = Material3Tokens.MEDIA_COVER_RADIUS;
    private static final float PROGRESS_HEIGHT = Material3Tokens.MEDIA_PROGRESS_HEIGHT;
    private static final float PROGRESS_RADIUS = Material3Tokens.MEDIA_PROGRESS_RADIUS;
    private static final int ELEVATION = Material3Tokens.ELEVATION_2;
    private static final float TITLE_TEXT_SIZE = 8.0f;
    private static final float ARTIST_TEXT_SIZE = 6.8f;
    private static final float TIME_TEXT_SIZE = 7.0f;
    private static final float ICON_SIZE = 5.2f;
    private static final float HEADER_ACTION_TEXT_SIZE = 5.85f;
    private static final float LYRICS_TEXT_SIZE = 6.5f;
    private static final float LYRICS_LINE_STEP = 9.8f;
    private static final float LYRICS_SCROLL_STEP = 12f;
    private static final float HEADER_ACTION_GAP = 5f;
    private static final float HEADER_ACTION_HIT_PADDING_X = 2f;
    private static final float HEADER_ACTION_HIT_PADDING_Y = 2f;
    private static final float HEADER_ACTION_UNDERLINE_HEIGHT = 0.9f;
    private static final float DIVIDER_HEIGHT = 1f;
    private static final float LYRICS_SECTION_TOP_GAP = 4f;
    private static final float LYRICS_TEXT_PADDING_X = 2f;
    private static final float LYRICS_TEXT_PADDING_Y = 2f;
    private static final float LYRICS_REVEAL_SLIDE = 6f;
    private static final float COVER_NEUTRAL_SATURATION = 0.08f;

    private static final String LYRICS_NOT_FOUND_TEXT = "Текст песни не найден";
    private static final String LYRICS_EMPTY_TEXT = "Текст песни отсутствует";

    private static final float GLOW_MIN_RADIUS = 1.0f;
    private static final float GLOW_MAX_RADIUS = 6f;
    private static float fastLevel = 0f;
    private static float sessionPeak = 1e-3f;
    private static float glowAmp = 0f;

    private static volatile String trackName = null;
    private static volatile String artistsText = null;
    private static volatile String source = null;
    private static volatile long totalTime = 1;

    private static volatile float progress = 0.0f;
    private static volatile long currentTime = 0;
    private static volatile boolean isPlaying = false;
    private static volatile boolean visualActive = false;

    private static float overlayAlpha = 0f;
    private static float displayProgress = 0f;
    private static float lyricsRevealProgress = 0f;
    private static final AnimationClock renderClock = new AnimationClock();

    private static final ExecutorService imageDecoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MediaPlayer-ImageDecoder");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final ExecutorService commandExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MediaPlayer-Commands");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean imageDecoding = new AtomicBoolean(false);
    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    private static volatile MediaSession session = null;
    private static volatile boolean hasActiveMedia = false;

    private static final Identifier coverTextureLocation = Identifier.of("mapplevisuals", "music_cover");
    private static final Identifier previousCoverTextureLocation = Identifier.of("mapplevisuals", "music_cover_prev");
    private static volatile NativeImageBackedTexture coverTexture = null;
    private static volatile NativeImageBackedTexture previousCoverTexture = null;
    private static final AtomicReference<byte[]> pendingAlbumArt = new AtomicReference<>(null);
    private static final AtomicReference<byte[]> currentAlbumArt = new AtomicReference<>(null);
    private static volatile boolean coverTextureRegistered = false;
    private static volatile boolean previousCoverTextureRegistered = false;
    private static volatile boolean coverCrossfading = false;
    private static volatile long coverCrossfadeStartNs = 0L;
    private static volatile MediaTheme coverTheme = null;

    private static volatile Lyrics lyrics = null;
    private static volatile boolean lyricsLoading = false;
    private static volatile String lyricsTrackKey = "";
    private static volatile String observedArtist = "";
    private static volatile String observedTitle = "";
    private static volatile float lyricsScrollOffset = 0f;
    private static volatile float lyricsManualScrollOffset = 0f;
    private static volatile float lyricsMaxScroll = 0f;
    private static volatile boolean lyricsManualScrollActive = false;
    private static volatile float syncedLyricsManualScrollOffset = 0f;
    private static volatile boolean syncedLyricsManualScrollActive = false;
    private static volatile boolean lyricsCollapsed = true;
    private static volatile boolean timelineScrubbing = false;
    private static volatile long timelineScrubPreviewMs = -1L;
    private static volatile float pointerX = 0f;
    private static volatile float pointerY = 0f;
    private static volatile boolean pointerTracked = false;

    public static float width = BOX_WIDTH;
    public static float height = COMPACT_BOX_HEIGHT;

    private record MediaTheme(int surface, int outline, int primary, int onSurface, int onSurfaceVariant,
                              int surfaceVariant, int outlineVariant, boolean coverBased) {
    }

    public MediaPlayerRenderer() {
        initializeSession();
    }

    private static final MediaListener LISTENER = new MediaListener() {
        @Override
        public void onTrackChanged(NowPlaying nowPlaying) {
            handleTrackChanged(nowPlaying);
        }

        @Override
        public void onPositionChanged(PlaybackPosition position) {
            handlePositionChanged(position);
        }

        @Override
        public void onPlaybackStopped() {
            handlePlaybackStopped();
        }
    };

    private static synchronized void initializeSession() {
        if (session != null || shuttingDown.get()) {
            return;
        }
        try {
            MediaSession opened = MediaSession.open();
            opened.addListener(LISTENER);
            session = opened;
            NowPlaying initial = opened.nowPlaying();
            if (initial != null && initial.title() != null && !initial.title().isEmpty()) {
                handleTrackChanged(initial);
            }
        } catch (Throwable t) {
            rethrowIfFatal(t);
            LOGGER.log(Level.WARNING, "[MediaPlayerRenderer] Failed to open MediaSession", t);
        }
    }

    public static void tick() {
        if (shuttingDown.get()) {
            return;
        }
        if (session == null) {
            initializeSession();
        }
    }

    private static void handleTrackChanged(NowPlaying np) {
        if (shuttingDown.get()) {
            return;
        }
        String title = np.title();
        if (title == null || title.isEmpty()) {
            return;
        }
        String rawArtist = np.artist();
        String rawSource = np.source();
        byte[] art = np.hasAlbumArt() ? np.albumArt() : null;
        long dur = np.durationMs() > 0 ? np.durationMs() : 1L;
        long pos = Math.max(0L, np.positionMs());
        boolean playing = np.isPlaying();

        MinecraftClient.getInstance().execute(() -> {
            hasActiveMedia = true;
            visualActive = true;
            trackName = title;
            artistsText = (rawArtist != null && !rawArtist.isEmpty()) ? rawArtist : UNKNOWN_ARTIST;
            source = (rawSource != null && !rawSource.isEmpty()) ? rawSource : UNKNOWN_SOURCE;

            if (art != null && art.length > 0) {
                byte[] previousArt = currentAlbumArt.get();
                if (previousArt == null || !Arrays.equals(previousArt, art)) {
                    pendingAlbumArt.set(art);
                    scheduleImageDecoding();
                }
            } else if (np.trackChanged()) {
                currentAlbumArt.set(null);
                coverTheme = null;
                clearCoverTexture();
                clearPreviousCoverTexture();
                coverCrossfading = false;
                coverCrossfadeStartNs = 0L;
            }

            totalTime = dur;
            if (!timelineScrubbing) {
                currentTime = pos;
                progress = clamp01((float) pos / (float) dur);
                displayProgress = progress;
            }
            isPlaying = playing;

            requestLyricsForTrack(rawArtist, title);
        });
    }

    private static void handlePositionChanged(PlaybackPosition pos) {
        if (shuttingDown.get()) {
            return;
        }
        long cur = Math.max(0L, pos.positionMs());
        long dur = pos.durationMs() > 0 ? pos.durationMs() : totalTime;
        boolean playing = pos.playing();

        MinecraftClient.getInstance().execute(() -> {
            if (trackName == null || trackName.isEmpty()) {
                return;
            }
            hasActiveMedia = true;
            visualActive = true;
            if (!timelineScrubbing) {
                currentTime = cur;
                progress = pos.durationMs() > 0 ? pos.progress() : clamp01((float) cur / (float) dur);
            }
            isPlaying = playing;
            if (dur > 0 && dur != totalTime) {
                totalTime = dur;
            }
        });
    }

    private static void handlePlaybackStopped() {
        if (shuttingDown.get()) {
            return;
        }
        MinecraftClient.getInstance().execute(() -> {
            hasActiveMedia = false;
            isPlaying = false;
            visualActive = false;
        });
        MediaSession s = session;
        if (s != null) {
            try {
                s.stopWaveform();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void requestLyricsForTrack(String rawArtist, String title) {
        MediaSession s = session;
        if (s == null) {
            return;
        }
        String artist = rawArtist == null ? "" : rawArtist;
        String key = lyricsKey(artist, title);
        if (key.isEmpty()) {
            lyrics = null;
            lyricsLoading = false;
            lyricsTrackKey = "";
            resetLyricsScroll();
            return;
        }
        if (key.equals(lyricsTrackKey)) {
            return;
        }
        lyricsTrackKey = key;
        observedArtist = artist;
        observedTitle = title;
        resetLyricsScroll();

        Lyrics cached = s.cachedLyrics(artist, title);
        if (cached != null) {
            lyrics = cached;
            lyricsLoading = false;
            return;
        }
        lyrics = null;
        lyricsLoading = true;
        s.requestLyrics(artist, title).whenComplete((result, ex) ->
                MinecraftClient.getInstance().execute(() -> {
                    if (key.equals(lyricsTrackKey)) {
                        lyrics = (ex == null && result != null) ? result : Lyrics.unavailable();
                        lyricsLoading = false;
                    }
                }));
    }

    private static void forceRetryLyricsRequest() {
        String artist = observedArtist;
        String title = observedTitle;
        if (title == null || title.isEmpty()) {
            return;
        }
        lyricsTrackKey = "";
        requestLyricsForTrack(artist, title);
    }

    private static String lyricsKey(String artist, String title) {
        String t = title == null ? "" : title.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty()) {
            return "";
        }
        String a = artist == null ? "" : artist.strip().toLowerCase(Locale.ROOT);
        return a + "|" + t;
    }

    private static boolean hasLyricsContent() {
        Lyrics l = lyrics;
        return l != null && l.isPresent();
    }

    private static boolean isLyricsPanelExpanded() {
        return hasLyricsContent() && !lyricsCollapsed;
    }

    private static boolean isSyncedLyrics() {
        Lyrics l = lyrics;
        return l != null && l.kind() == Lyrics.Kind.SYNCED;
    }

    private static boolean isSyncedManualScrollMode() {
        return isSyncedLyrics() && syncedLyricsManualScrollActive;
    }

    private static boolean isLyricsNotFound() {
        Lyrics l = lyrics;
        return l != null && !l.isPresent() && l.available();
    }

    private static boolean canManualRetryLyrics() {
        Lyrics l = lyrics;
        return l != null && !l.isPresent() && !l.available();
    }

    private static boolean shouldShowLyricsStatusBadge() {
        if (hasLyricsContent()) {
            return false;
        }
        return lyricsLoading || isLyricsNotFound() || canManualRetryLyrics();
    }

    private static String getLyricsStatusText() {
        if (lyricsLoading) {
            return "Ищем текст";
        }
        if (canManualRetryLyrics()) {
            return "Повторить";
        }
        if (isLyricsNotFound()) {
            return "Нет текста";
        }
        return "";
    }

    private static void resetLyricsScroll() {
        lyricsScrollOffset = 0f;
        lyricsManualScrollOffset = 0f;
        lyricsManualScrollActive = false;
        syncedLyricsManualScrollOffset = 0f;
        syncedLyricsManualScrollActive = false;
        lyricsMaxScroll = 0f;
        timelineScrubbing = false;
        timelineScrubPreviewMs = -1L;
    }

    private static void clearLyricsTracking() {
        lyrics = null;
        lyricsLoading = false;
        lyricsTrackKey = "";
        observedArtist = "";
        observedTitle = "";
        resetLyricsScroll();
    }

    private static MediaLayout createLayout(float x, float y) {
        return createLayout(x, y, Math.max(BOX_WIDTH, width), Math.max(COMPACT_BOX_HEIGHT, height));
    }

    private static MediaLayout createLayout(float x, float y, float boxWidth, float boxHeight) {
        HeaderActionMetrics actionMetrics = resolveHeaderActionMetrics();
        float coverX = x + CONTENT_PADDING;
        float coverY = y + (HEADER_HEIGHT - COVER_SIZE) * 0.5f;
        float textX = x + COVER_SIZE + CONTENT_PADDING * 2f;
        float titleY = y + 7.5f;
        float artistY = y + 17.5f;
        float actionY = titleY;

        float titleActionGap = actionMetrics.totalWidth() > 0f ? 5f : 0f;
        float titleTextWidth = Math.max(0f, boxWidth - (textX - x) - CONTENT_PADDING - actionMetrics.totalWidth() - titleActionGap);
        float artistTextWidth = Math.max(0f, boxWidth - (textX - x) - CONTENT_PADDING);

        float progressBarY = y + 28.5f;
        float progressBarLeft = textX;
        float progressBarRight = x + boxWidth - CONTENT_PADDING;
        float progressBarWidth = Math.max(0f, progressBarRight - progressBarLeft);

        float fixedTimeW = SMALL_TEXT_RENDERER.width("00:00", TIME_TEXT_SIZE);
        float currentTimeX = progressBarLeft;
        float totalTimeX = progressBarRight - fixedTimeW;

        float prevW = ICON_RENDERER.width("N", ICON_SIZE);
        float playW = ICON_RENDERER.width(isPlaying ? "Q" : "O", ICON_SIZE);
        float playSlotW = Math.max(ICON_RENDERER.width("Q", ICON_SIZE), ICON_RENDERER.width("O", ICON_SIZE));
        float nextW = ICON_RENDERER.width("P", ICON_SIZE);
        float controlsGap = 5f;
        float controlsWidth = prevW + controlsGap + playSlotW + controlsGap + nextW;

        float bottomCenterY = coverY + COVER_SIZE - 2.5f;
        float timeY = bottomCenterY - TIME_TEXT_SIZE * 0.5f;
        float controlsY = bottomCenterY - ICON_SIZE * 0.5f;

        float currentTimeEnd = currentTimeX + fixedTimeW;
        float totalTimeStart = totalTimeX;
        float gapAvailable = Math.max(0f, totalTimeStart - currentTimeEnd);
        float controlsStartX = currentTimeEnd + Math.max(0f, (gapAvailable - controlsWidth) * 0.5f);

        float prevX = controlsStartX;
        float playX = prevX + prevW + controlsGap + (playSlotW - playW) * 0.5f;
        float nextX = prevX + prevW + controlsGap + playSlotW + controlsGap;

        float dividerY = y + HEADER_HEIGHT;
        float lyricsX = x + CONTENT_PADDING;
        float lyricsY = dividerY + DIVIDER_HEIGHT + LYRICS_SECTION_TOP_GAP;
        float lyricsWidth = boxWidth - CONTENT_PADDING * 2f;
        float lyricsHeight = Math.max(0f, boxHeight - (lyricsY - y) - CONTENT_PADDING);

        return new MediaLayout(
                boxWidth, boxHeight, coverX, coverY, textX, titleY, artistY, titleTextWidth, artistTextWidth, actionY,
                progressBarY, progressBarLeft, progressBarRight, progressBarWidth, currentTimeX, totalTimeX, timeY, controlsY,
                prevX, prevW, playX, playW, playSlotW, nextX, nextW, dividerY, lyricsX, lyricsY, lyricsWidth, lyricsHeight);
    }

    private static HeaderActionMetrics resolveHeaderActionMetrics() {
        String toggleLabel = hasLyricsContent()
                ? (isLyricsPanelExpanded() ? "Скрыть текст" : "Открыть текст")
                : "";
        String syncLabel = isSyncedManualScrollMode() && isLyricsPanelExpanded()
                ? "К текущей"
                : "";
        String statusLabel = shouldShowLyricsStatusBadge() ? getLyricsStatusText() : "";

        float toggleWidth = toggleLabel.isEmpty() ? 0f : measureTextWidth(toggleLabel, HEADER_ACTION_TEXT_SIZE);
        float syncWidth = syncLabel.isEmpty() ? 0f : measureTextWidth(syncLabel, HEADER_ACTION_TEXT_SIZE);
        float statusWidth = statusLabel.isEmpty() ? 0f : measureTextWidth(statusLabel, HEADER_ACTION_TEXT_SIZE);
        float totalWidth = 0f;
        if (statusWidth > 0f) {
            totalWidth += statusWidth;
        }
        if (syncWidth > 0f) {
            totalWidth += syncWidth + (totalWidth > 0f ? HEADER_ACTION_GAP : 0f);
        }
        if (toggleWidth > 0f) {
            totalWidth += toggleWidth + (totalWidth > 0f ? HEADER_ACTION_GAP : 0f);
        }
        return new HeaderActionMetrics(toggleLabel, toggleWidth, syncLabel, syncWidth, statusLabel, statusWidth, totalWidth);
    }

    private static HeaderActions createHeaderActions(float rootX, MediaLayout layout) {
        HeaderActionMetrics metrics = resolveHeaderActionMetrics();
        float actionHeight = SMALL_TEXT_RENDERER.height("Ag", HEADER_ACTION_TEXT_SIZE);
        float cursor = rootX + layout.boxWidth() - CONTENT_PADDING;

        TextAction toggleAction = TextAction.empty();
        TextAction syncAction = TextAction.empty();
        TextAction statusAction = TextAction.empty();

        if (metrics.toggleWidth() > 0f) {
            cursor -= metrics.toggleWidth();
            toggleAction = new TextAction("toggle", metrics.toggleLabel(), cursor, layout.actionY(), metrics.toggleWidth(),
                    actionHeight, true, true);
            cursor -= HEADER_ACTION_GAP;
        }

        if (metrics.syncWidth() > 0f) {
            cursor -= metrics.syncWidth();
            syncAction = new TextAction("sync", metrics.syncLabel(), cursor, layout.actionY(), metrics.syncWidth(),
                    actionHeight, true, true);
            cursor -= HEADER_ACTION_GAP;
        }

        if (metrics.statusWidth() > 0f) {
            cursor -= metrics.statusWidth();
            statusAction = new TextAction("status", metrics.statusLabel(), cursor, layout.actionY(), metrics.statusWidth(),
                    actionHeight, canManualRetryLyrics(), true);
        }

        return new HeaderActions(toggleAction, syncAction, statusAction);
    }

    private static TextAction findHeaderActionAt(float rootX, MediaLayout layout, float mouseX, float mouseY) {
        HeaderActions actions = createHeaderActions(rootX, layout);
        if (isPointInsideAction(actions.toggle(), mouseX, mouseY)) {
            return actions.toggle();
        }
        if (isPointInsideAction(actions.sync(), mouseX, mouseY)) {
            return actions.sync();
        }
        if (isPointInsideAction(actions.status(), mouseX, mouseY)) {
            return actions.status();
        }
        return TextAction.empty();
    }

    private static boolean isPointInsideAction(TextAction action, float mouseX, float mouseY) {
        if (!action.visible()) {
            return false;
        }
        return mouseX >= action.x() - HEADER_ACTION_HIT_PADDING_X
                && mouseX <= action.x() + action.width() + HEADER_ACTION_HIT_PADDING_X
                && mouseY >= action.y() - HEADER_ACTION_HIT_PADDING_Y
                && mouseY <= action.y() + action.height() + HEADER_ACTION_HIT_PADDING_Y;
    }

    private static void scheduleImageDecoding() {
        if (shuttingDown.get() || !imageDecoding.compareAndSet(false, true)) {
            return;
        }
        try {
            imageDecoder.execute(() -> {
                NativeImage decodedImage = null;
                NativeImage decodedPreviousImage = null;
                boolean handedOffToMainThread = false;
                try {
                    byte[] albumArt = pendingAlbumArt.getAndSet(null);
                    if (albumArt == null || albumArt.length == 0) {
                        return;
                    }

                    decodedImage = NativeImage.read(new ByteArrayInputStream(albumArt));
                    MediaTheme decodedCoverTheme = createCoverTheme(decodedImage);
                    byte[] previousAlbumArt = currentAlbumArt.get();
                    if (previousAlbumArt != null && previousAlbumArt.length > 0) {
                        try {
                            decodedPreviousImage = NativeImage.read(new ByteArrayInputStream(previousAlbumArt));
                        } catch (Exception ignored) {
                            decodedPreviousImage = null;
                        }
                    }

                    NativeImage finalDecodedImage = decodedImage;
                    NativeImage finalDecodedPreviousImage = decodedPreviousImage;
                    MediaTheme finalCoverTheme = decodedCoverTheme;
                    byte[] finalAlbumArt = albumArt;
                    decodedImage = null;
                    decodedPreviousImage = null;

                    MinecraftClient.getInstance().execute(() -> {
                        NativeImage newImage = finalDecodedImage;
                        NativeImage oldImage = finalDecodedPreviousImage;
                        try {
                            TextureManager tm = MinecraftClient.getInstance().getTextureManager();

                            clearPreviousCoverTexture();
                            if (oldImage != null) {
                                previousCoverTexture = new NativeImageBackedTexture(() -> "prev_media_cover", oldImage);
                                tm.registerTexture(previousCoverTextureLocation, previousCoverTexture);
                                previousCoverTextureRegistered = true;
                                oldImage = null;
                            }

                            clearCoverTexture();
                            coverTexture = new NativeImageBackedTexture(() -> "media_cover", newImage);
                            tm.registerTexture(coverTextureLocation, coverTexture);
                            coverTextureRegistered = true;
                            newImage = null;

                            currentAlbumArt.set(finalAlbumArt);
                            coverTheme = finalCoverTheme;
                            if (previousCoverTextureRegistered) {
                                coverCrossfading = true;
                                coverCrossfadeStartNs = System.nanoTime();
                            } else {
                                coverCrossfading = false;
                                coverCrossfadeStartNs = 0L;
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "[MediaPlayerRenderer] Texture update failed", e);
                            try {
                                if (newImage != null) {
                                    newImage.close();
                                }
                            } catch (Exception ignored) {
                            }
                            try {
                                if (oldImage != null) {
                                    oldImage.close();
                                }
                            } catch (Exception ignored) {
                            }
                            clearCoverTexture();
                            clearPreviousCoverTexture();
                            coverCrossfading = false;
                            coverCrossfadeStartNs = 0L;
                        } finally {
                            imageDecoding.set(false);
                        }
                    });
                    handedOffToMainThread = true;
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "[MediaPlayerRenderer] Image decode failed", e);
                } finally {
                    if (decodedImage != null) {
                        try {
                            decodedImage.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (decodedPreviousImage != null) {
                        try {
                            decodedPreviousImage.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (!handedOffToMainThread) {
                        imageDecoding.set(false);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            imageDecoding.set(false);
        }
    }

    private static MediaTheme resolveMediaTheme() {
        MediaTheme albumTheme = coverTheme;
        if (isCoverThemeEnabled() && albumTheme != null) {
            return albumTheme;
        }
        return clientTheme();
    }

    private static boolean isCoverThemeEnabled() {
        return Interface.mediaPlayerTheme != null && Interface.mediaPlayerTheme.is("Обложка");
    }

    private static MediaTheme clientTheme() {
        return new MediaTheme(
                Material3Colors.getSurfaceAtElevation(ELEVATION),
                Material3Colors.getOutlineVariant(),
                Material3Colors.getPrimary(),
                Material3Colors.getOnSurface(),
                Material3Colors.getOnSurfaceVariant(),
                Material3Colors.getSurfaceVariant(),
                Material3Colors.getOutlineVariant(),
                false
        );
    }

    private static MediaTheme createCoverTheme(NativeImage image) {
        try {
            VibrantPalette palette = Vibrant.from(image).getPalette();
            int fallbackPrimary = Material3Colors.getPrimary();
            int accentSeed = palette.vibrantOr(palette.lightVibrantOr(palette.mutedOr(fallbackPrimary)));
            int surfaceSeed = palette.darkMutedOr(palette.darkVibrantOr(palette.mutedOr(accentSeed)));
            boolean neutralCover = isNeutralColor(accentSeed) && isNeutralColor(surfaceSeed);

            int surface = neutralCover ? coverNeutralSurface(surfaceSeed) : coverSurface(surfaceSeed);
            int primary = neutralCover ? coverNeutralPrimary(accentSeed, surface) : coverPrimary(accentSeed, surface);
            int onSurface = readableOn(surface);
            int onSurfaceVariant = ensureContrast(mix(onSurface, primary, 0.22f), surface, 4.5f, luminance(onSurface) > luminance(surface));
            int surfaceVariant = ensureContrast(mix(surface, primary, 0.18f), surface, 1.45f, luminance(onSurface) > luminance(surface));
            int outline = ensureContrast(mix(surfaceVariant, onSurfaceVariant, 0.42f), surface, 2.0f, luminance(onSurface) > luminance(surface));

            return new MediaTheme(surface, outline, primary, onSurface, onSurfaceVariant, surfaceVariant, outline, true);
        } catch (Throwable t) {
            rethrowIfFatal(t);
            LOGGER.log(Level.FINE, "[MediaPlayerRenderer] Cover palette extraction failed", t);
            return null;
        }
    }

    private static boolean isNeutralColor(int color) {
        return rgbToHsl(color)[1] <= COVER_NEUTRAL_SATURATION;
    }

    private static int coverNeutralSurface(int seed) {
        float luma = rgbToHsl(seed)[2] < 0.12f ? 0.13f : 0.105f;
        return hslToRgb(0f, 0f, luma);
    }

    private static int coverNeutralPrimary(int seed, int surface) {
        float[] hsl = rgbToHsl(seed);
        float luma = Math.max(0.58f, Math.min(0.78f, hsl[2] < 0.42f ? hsl[2] + 0.30f : hsl[2]));
        return ensureContrast(hslToRgb(0f, 0f, luma), surface, 3.2f, true);
    }

    private static int coverSurface(int seed) {
        float[] hsl = rgbToHsl(seed);
        float saturation = Math.min(0.38f, Math.max(0.16f, hsl[1] * 0.72f));
        float luma = hsl[2] < 0.12f ? 0.13f : 0.105f;
        return hslToRgb(hsl[0], saturation, luma);
    }

    private static int coverPrimary(int seed, int surface) {
        float[] hsl = rgbToHsl(seed);
        float saturation = Math.min(0.92f, Math.max(0.52f, hsl[1] * 1.12f));
        float luma = Math.max(0.58f, Math.min(0.78f, hsl[2] < 0.42f ? hsl[2] + 0.30f : hsl[2]));
        int primary = hslToRgb(hsl[0], saturation, luma);
        return ensureContrast(primary, surface, 3.2f, true);
    }

    private static int ensureContrast(int foreground, int background, float minRatio, boolean lighten) {
        int target = lighten ? 0xFFFFFFFF : 0xFF000000;
        int color = foreground;
        for (int i = 0; i < 18 && contrastRatio(color, background) < minRatio; i++) {
            color = mix(color, target, 0.12f);
        }
        return color;
    }

    private static float contrastRatio(int a, int b) {
        float l1 = luminance(a) + 0.05f;
        float l2 = luminance(b) + 0.05f;
        return Math.max(l1, l2) / Math.min(l1, l2);
    }

    private static int readableOn(int background) {
        return luminance(background) > 0.56f ? 0xFF111217 : 0xFFFFFFFF;
    }

    private static float luminance(int color) {
        float r = linear(((color >>> 16) & 0xFF) / 255f);
        float g = linear(((color >>> 8) & 0xFF) / 255f);
        float b = linear((color & 0xFF) / 255f);
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }

    private static float linear(float value) {
        return value <= 0.03928f
                ? value / 12.92f
                : (float) Math.pow((value + 0.055f) / 1.055f, 2.4f);
    }

    private static float[] rgbToHsl(int color) {
        float r = ((color >>> 16) & 0xFF) / 255f;
        float g = ((color >>> 8) & 0xFF) / 255f;
        float b = (color & 0xFF) / 255f;
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float h = 0f;
        float s = 0f;
        float l = (max + min) * 0.5f;

        if (max != min) {
            float delta = max - min;
            s = l > 0.5f ? delta / (2f - max - min) : delta / (max + min);
            if (max == r) {
                h = (g - b) / delta + (g < b ? 6f : 0f);
            } else if (max == g) {
                h = (b - r) / delta + 2f;
            } else {
                h = (r - g) / delta + 4f;
            }
            h /= 6f;
        }

        return new float[]{h, s, l};
    }

    private static int hslToRgb(float h, float s, float l) {
        float r;
        float g;
        float b;
        if (s == 0f) {
            r = g = b = l;
        } else {
            float q = l < 0.5f ? l * (1f + s) : l + s - l * s;
            float p = 2f * l - q;
            r = hueToRgb(p, q, h + 1f / 3f);
            g = hueToRgb(p, q, h);
            b = hueToRgb(p, q, h - 1f / 3f);
        }

        return 0xFF000000
                | (Math.round(r * 255f) << 16)
                | (Math.round(g * 255f) << 8)
                | Math.round(b * 255f);
    }

    private static float hueToRgb(float p, float q, float t) {
        if (t < 0f) {
            t += 1f;
        }
        if (t > 1f) {
            t -= 1f;
        }
        if (t < 1f / 6f) {
            return p + (q - p) * 6f * t;
        }
        if (t < 1f / 2f) {
            return q;
        }
        if (t < 2f / 3f) {
            return p + (q - p) * (2f / 3f - t) * 6f;
        }
        return p;
    }

    private static int mix(int from, int to, float amount) {
        amount = Math.max(0f, Math.min(1f, amount));
        int r = Math.round(((from >>> 16) & 0xFF) + (((to >>> 16) & 0xFF) - ((from >>> 16) & 0xFF)) * amount);
        int g = Math.round(((from >>> 8) & 0xFF) + (((to >>> 8) & 0xFF) - ((from >>> 8) & 0xFF)) * amount);
        int b = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    public static void clearData() {
        hasActiveMedia = false;
        visualActive = false;
        overlayAlpha = 0f;
        lyricsRevealProgress = 0f;
        trackName = null;
        artistsText = null;
        source = null;
        progress = 0.0f;
        displayProgress = 0.0f;
        currentTime = 0;
        totalTime = 1;
        isPlaying = false;
        currentAlbumArt.set(null);
        coverTheme = null;
        coverCrossfading = false;
        coverCrossfadeStartNs = 0L;
        clearLyricsTracking();
        clearCoverTexture();
        clearPreviousCoverTexture();
        fastLevel = 0f;
        sessionPeak = 1e-3f;
        glowAmp = 0f;
        width = BOX_WIDTH;
        height = COMPACT_BOX_HEIGHT;
        MediaSession s = session;
        if (s != null) {
            try {
                s.stopWaveform();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void clearCoverTexture() {
        if (coverTexture != null) {
            TextureManager tm = MinecraftClient.getInstance().getTextureManager();
            try {
                tm.destroyTexture(coverTextureLocation);
                coverTexture.close();
            } catch (Exception ignored) {
            }
            coverTexture = null;
        }
        coverTextureRegistered = false;
    }

    private static void clearPreviousCoverTexture() {
        if (previousCoverTexture != null) {
            TextureManager tm = MinecraftClient.getInstance().getTextureManager();
            try {
                tm.destroyTexture(previousCoverTextureLocation);
                previousCoverTexture.close();
            } catch (Exception ignored) {
            }
            previousCoverTexture = null;
        }
        previousCoverTextureRegistered = false;
    }

    private static void clearStaleDataAfterFadeOut() {
        if (visualActive || overlayAlpha > 0.01f) {
            return;
        }

        trackName = null;
        artistsText = null;
        source = null;
        progress = 0.0f;
        displayProgress = 0.0f;
        currentTime = 0;
        totalTime = 1;
        isPlaying = false;
        currentAlbumArt.set(null);
        coverTheme = null;
        coverCrossfading = false;
        coverCrossfadeStartNs = 0L;
        clearLyricsTracking();
        clearCoverTexture();
        clearPreviousCoverTexture();
        fastLevel = 0f;
        sessionPeak = 1e-3f;
        glowAmp = 0f;
        lyricsRevealProgress = 0f;
        width = BOX_WIDTH;
        height = COMPACT_BOX_HEIGHT;
        MediaSession s = session;
        if (s != null) {
            try {
                s.stopWaveform();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String formatTime(long time) {
        if (time < 0) {
            time = 0;
        }
        long totalSeconds = time / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        String minuteText = minutes < 10 ? ("0" + minutes) : Long.toString(minutes);
        String secondText = seconds < 10 ? ("0" + seconds) : Long.toString(seconds);
        return minuteText + ":" + secondText;
    }

    private static final MsdfTextRenderer TITLE_RENDERER = FontLayout.SEMIBOLD;
    private static final MsdfTextRenderer SMALL_TEXT_RENDERER = FontLayout.MEDIUM;
    private static final MsdfTextRenderer ICON_RENDERER = FontLayout.ICON_RENDERER;

    private static final ConcurrentMap<String, Float> textWidthCache = new ConcurrentHashMap<>(2048);
    private static final Map<WrappedLyricsCacheKey, WrappedLyricsLayout> wrappedLyricsCache = Collections.synchronizedMap(
            new LinkedHashMap<>(WRAPPED_LYRICS_CACHE_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<WrappedLyricsCacheKey, WrappedLyricsLayout> eldest) {
                    return size() > WRAPPED_LYRICS_CACHE_CAPACITY;
                }
            });

    private static void updatePointerState(float mouseX, float mouseY) {
        pointerX = mouseX;
        pointerY = mouseY;
        pointerTracked = true;
    }

    private static float measureTextWidth(String text, float size) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        String key = size + "|" + text;
        Float cached = textWidthCache.get(key);
        if (cached != null) {
            return cached;
        }
        float widthValue = SMALL_TEXT_RENDERER.width(text, size);
        textWidthCache.put(key, widthValue);
        return widthValue;
    }

    private static WrappedLyricsLayout getWrappedLyricsLayout(Lyrics payload, float maxWidth) {
        if (payload == null || !payload.isPresent()) {
            return WrappedLyricsLayout.EMPTY;
        }
        int widthKey = Math.max(1, Math.round(maxWidth * 10f));
        int contentHash = payload.kind() == Lyrics.Kind.SYNCED
                ? payload.syncedLines().hashCode()
                : payload.plainLines().hashCode();
        WrappedLyricsCacheKey cacheKey = new WrappedLyricsCacheKey(lyricsTrackKey, payload.kind(), contentHash, widthKey);
        WrappedLyricsLayout cached;
        synchronized (wrappedLyricsCache) {
            cached = wrappedLyricsCache.get(cacheKey);
        }
        if (cached != null) {
            return cached;
        }

        WrappedLyricsLayout created = payload.kind() == Lyrics.Kind.SYNCED
                ? buildWrappedSyncedLyricsLayout(payload.syncedLines(), maxWidth)
                : buildWrappedPlainLyricsLayout(payload.plainLines(), maxWidth);
        synchronized (wrappedLyricsCache) {
            wrappedLyricsCache.put(cacheKey, created);
        }
        return created;
    }

    private static WrappedLyricsLayout buildWrappedSyncedLyricsLayout(List<LyricLine> sourceLines, float maxWidth) {
        if (sourceLines.isEmpty()) {
            return WrappedLyricsLayout.EMPTY;
        }
        List<WrappedLyricLine> wrappedLines = new ArrayList<>();
        int[] firstVisualIndexBySource = new int[sourceLines.size()];
        Arrays.fill(firstVisualIndexBySource, -1);

        for (int i = 0; i < sourceLines.size(); i++) {
            firstVisualIndexBySource[i] = wrappedLines.size();
            LyricLine sourceLine = sourceLines.get(i);
            List<String> visualParts = wrapLyricLine(sourceLine.text(), maxWidth);
            if (visualParts.isEmpty()) {
                visualParts = List.of("");
            }
            for (String part : visualParts) {
                wrappedLines.add(new WrappedLyricLine(part, i, sourceLine.timeMs(), part.isBlank()));
            }
        }

        return new WrappedLyricsLayout(List.copyOf(wrappedLines), firstVisualIndexBySource);
    }

    private static WrappedLyricsLayout buildWrappedPlainLyricsLayout(List<String> sourceLines, float maxWidth) {
        if (sourceLines.isEmpty()) {
            return WrappedLyricsLayout.EMPTY;
        }
        List<WrappedLyricLine> wrappedLines = new ArrayList<>();
        int[] firstVisualIndexBySource = new int[sourceLines.size()];
        Arrays.fill(firstVisualIndexBySource, -1);

        for (int i = 0; i < sourceLines.size(); i++) {
            firstVisualIndexBySource[i] = wrappedLines.size();
            List<String> visualParts = wrapLyricLine(sourceLines.get(i), maxWidth);
            if (visualParts.isEmpty()) {
                visualParts = List.of("");
            }
            for (String part : visualParts) {
                wrappedLines.add(new WrappedLyricLine(part, i, -1L, part.isBlank()));
            }
        }

        return new WrappedLyricsLayout(List.copyOf(wrappedLines), firstVisualIndexBySource);
    }

    private static List<String> wrapLyricLine(String rawText, float maxWidth) {
        if (rawText == null) {
            return List.of("");
        }
        String normalized = rawText.replace('\t', ' ').strip();
        if (normalized.isEmpty()) {
            return List.of("");
        }
        normalized = MULTISPACE.matcher(normalized).replaceAll(" ");
        if (measureTextWidth(normalized, LYRICS_TEXT_SIZE) <= maxWidth) {
            return List.of(normalized);
        }

        List<String> wrappedLines = new ArrayList<>();
        String current = "";
        for (String word : normalized.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            current = appendWrappedWord(wrappedLines, current, word, maxWidth);
        }
        if (!current.isEmpty()) {
            wrappedLines.add(current);
        }
        return wrappedLines.isEmpty() ? List.of(normalized) : List.copyOf(wrappedLines);
    }

    private static String appendWrappedWord(List<String> wrappedLines, String currentLine, String word, float maxWidth) {
        String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
        if (measureTextWidth(candidate, LYRICS_TEXT_SIZE) <= maxWidth) {
            return candidate;
        }
        if (!currentLine.isEmpty()) {
            wrappedLines.add(currentLine);
            return appendWrappedWord(wrappedLines, "", word, maxWidth);
        }

        List<String> splitWordParts = splitLongToken(word, maxWidth);
        if (splitWordParts.isEmpty()) {
            return "";
        }
        for (int i = 0; i < splitWordParts.size() - 1; i++) {
            wrappedLines.add(splitWordParts.get(i));
        }
        return splitWordParts.get(splitWordParts.size() - 1);
    }

    private static List<String> splitLongToken(String token, float maxWidth) {
        if (token.isEmpty()) {
            return List.of("");
        }
        if (measureTextWidth(token, LYRICS_TEXT_SIZE) <= maxWidth) {
            return List.of(token);
        }

        List<String> parts = new ArrayList<>();
        int start = 0;
        while (start < token.length()) {
            int bestEnd = start + 1;
            for (int end = start + 1; end <= token.length(); end++) {
                String segment = token.substring(start, end);
                if (measureTextWidth(segment, LYRICS_TEXT_SIZE) <= maxWidth) {
                    bestEnd = end;
                } else {
                    break;
                }
            }
            parts.add(token.substring(start, bestEnd));
            start = bestEnd;
        }
        return List.copyOf(parts);
    }

    private static void updateGlow(float deltaTime) {
        MediaSession s = session;
        if (s == null || !isPlaying || !visualActive || overlayAlpha <= 0.01f) {
            fastLevel = 0f;
            glowAmp += (0f - glowAmp) * Math.min(1f, deltaTime * 8f);
            return;
        }

        float level;
        try {
            level = s.audioLevel();
        } catch (Throwable t) {
            rethrowIfFatal(t);
            return;
        }

        float fastK = level > fastLevel
                ? Math.min(1f, deltaTime * 40f)
                : Math.min(1f, deltaTime * 5f);
        fastLevel += (level - fastLevel) * fastK;

        if (fastLevel > sessionPeak) {
            sessionPeak = fastLevel;
        } else {
            sessionPeak = Math.max(1e-3f, sessionPeak + (1e-3f - sessionPeak) * Math.min(1f, deltaTime * 0.012f));
        }

        float rawNorm = clamp01(fastLevel / sessionPeak * 1.1f - 0.05f);

        glowAmp += (rawNorm - glowAmp) * Math.min(1f, deltaTime * (rawNorm > glowAmp ? 15f : 8f));
    }

    private static void renderCoverGlow(DrawContext context,
                                        MediaLayout layout, MediaTheme theme, float rootX, float rootY) {
        if (glowAmp <= 0.01f || overlayAlpha <= 0.01f) return;

        float amp = clamp01(glowAmp);
        float glowRadius = GLOW_MIN_RADIUS + amp * (GLOW_MAX_RADIUS - GLOW_MIN_RADIUS);
        float intensity = amp * 0.75f;

        RenderUtils.coverGlow(
                context,
                layout.coverX(), layout.coverY(),
                COVER_SIZE, COVER_RADIUS,
                theme.primary(),
                glowRadius, intensity,
                overlayAlpha
        );
    }

    public static void render(EventRender.Screen event, float x, float y) {
        long nowNs = System.nanoTime();
        float deltaTime = UiAnimationManager.deltaSeconds(renderClock);

        boolean isChat = MinecraftClient.getInstance().currentScreen instanceof ChatScreen;
        boolean hasTrackData = trackName != null && !trackName.isEmpty();
        float alphaTarget = (visualActive && hasTrackData) || isChat ? 1f : 0f;
        overlayAlpha = UiAnimationManager.animateAlpha(overlayAlpha, alphaTarget, deltaTime);

        if (!hasTrackData && !isChat && overlayAlpha <= 0.01f) {
            return;
        }

        if (overlayAlpha <= 0.01f && alphaTarget <= 0f) {
            clearStaleDataAfterFadeOut();
            return;
        }

        String effectiveTrackName = hasTrackData ? trackName : "Media Player";
        String effectiveArtistsText = (artistsText != null && !artistsText.isEmpty()) ? artistsText : "No track playing";

        updateGlow(deltaTime);

        if (!timelineScrubbing && session != null && isPlaying) {
            PlaybackPosition livePos = session.position();
            if (livePos != null) {
                currentTime = livePos.positionMs();
                progress = livePos.progress();
                if (livePos.durationMs() > 0 && livePos.durationMs() != totalTime) {
                    totalTime = livePos.durationMs();
                }
            }
        }

        displayProgress = timelineScrubbing
                ? progress
                : UiAnimationManager.animateSelection(displayProgress, progress, deltaTime);
        lyricsRevealProgress = UiAnimationManager.animatePanel(lyricsRevealProgress, isLyricsPanelExpanded() ? 1f : 0f, deltaTime);
        width = UiAnimationManager.animateWidth(width, BOX_WIDTH, deltaTime);
        float targetHeight = COMPACT_BOX_HEIGHT + (EXPANDED_BOX_HEIGHT - COMPACT_BOX_HEIGHT) * lyricsRevealProgress;
        height = UiAnimationManager.animateHeight(height, targetHeight, deltaTime);

        var context = event.getContext();
        MediaLayout layout = createLayout(x, y, width, height);
        width = layout.boxWidth();
        height = layout.boxHeight();

        final float radius = BOX_RADIUS;

        String currentTimeStr = formatTime(currentTime);
        String totalTimeStr = formatTime(totalTime);
        HeaderActions headerActions = createHeaderActions(x, layout);
        TextAction hoveredAction = TextAction.empty();
        if (pointerTracked && MinecraftClient.getInstance().currentScreen instanceof ChatScreen) {
            hoveredAction = findHeaderActionAt(x, layout, pointerX, pointerY);
        }
        float titleFadeW = layout.titleTextWidth();
        float titleFadeS = Math.min(14f, titleFadeW);
        float artistFadeW = layout.artistTextWidth();
        float artistFadeS = Math.min(14f, artistFadeW);

        int textAlpha = Math.round(255f * overlayAlpha);
        int mutedTextAlpha = Math.round(180f * overlayAlpha);

        MediaTheme theme = resolveMediaTheme();
        int bgColor = Material3Colors.withAlpha(theme.surface(), Math.round(255f * 0.88f * overlayAlpha));
        int outlineColor = Material3Colors.withAlpha(theme.outline(), Math.round(105f * overlayAlpha));
        int primaryColor = theme.primary();
        int onSurfaceColor = theme.onSurface();
        int onSurfaceVariantColor = theme.onSurfaceVariant();
        RenderUtils.roundedOutlinedRect(context, x, y, layout.boxWidth(), layout.boxHeight(), radius,
                bgColor, 1.0f, outlineColor);

        float coverBlend = 1f;
        if (coverCrossfading) {
            float rawBlend = (float) (nowNs - coverCrossfadeStartNs) / (float) COVER_CROSSFADE_DURATION_NS;
            coverBlend = Math.max(0f, Math.min(1f, rawBlend));
            if (coverBlend >= 1f) {
                coverCrossfading = false;
                coverCrossfadeStartNs = 0L;
                clearPreviousCoverTexture();
            }
        }

        renderCoverGlow(context, layout, theme, x, y);

        TextureManager textureManager = MinecraftClient.getInstance().getTextureManager();
        boolean drewCover = false;

        if (previousCoverTexture != null && previousCoverTextureRegistered) {
            var tex = textureManager.getTexture(previousCoverTextureLocation);
            if (tex != null) {
                float oldCoverOpacity = Math.max(0f, Math.min(1f, overlayAlpha * (1f - coverBlend)));
                if (oldCoverOpacity > 0.001f) {
                    RenderUtils.roundedTexture(context, previousCoverTextureLocation, layout.coverX(), layout.coverY(),
                            COVER_SIZE, COVER_SIZE, 0f, 0f, 1f, 1f, COVER_RADIUS, COVER_RADIUS, COVER_RADIUS, COVER_RADIUS,
                            0xFFFFFFFF, oldCoverOpacity);
                    drewCover = true;
                }
            }
        }

        if (coverTexture != null && coverTextureRegistered) {
            var tex = textureManager.getTexture(coverTextureLocation);
            if (tex != null) {
                float newCoverOpacity = previousCoverTextureRegistered
                        ? Math.max(0f, Math.min(1f, overlayAlpha * coverBlend))
                        : overlayAlpha;
                RenderUtils.roundedTexture(context, coverTextureLocation, layout.coverX(), layout.coverY(),
                        COVER_SIZE, COVER_SIZE, 0f, 0f, 1f, 1f, COVER_RADIUS, COVER_RADIUS, COVER_RADIUS, COVER_RADIUS,
                        0xFFFFFFFF, newCoverOpacity);
                drewCover = true;
            }
        }

        if (!drewCover) {
            renderPlaceholderCover(context, layout.coverX(), layout.coverY(), COVER_SIZE, overlayAlpha, theme);
        }

        try (MsdfBatch ignored = MsdfBatch.begin(context, 2)) {
            TITLE_RENDERER.drawWithWidthFade(context, effectiveTrackName, layout.textX(), layout.titleY(),
                    Material3Colors.withAlpha(onSurfaceColor, textAlpha), TITLE_TEXT_SIZE, titleFadeW, titleFadeS);

            if (effectiveArtistsText != null && !effectiveArtistsText.isEmpty()) {
                int artistColor = Material3Colors.withAlpha(onSurfaceVariantColor, Math.round(220f * overlayAlpha));
                SMALL_TEXT_RENDERER.drawWithWidthFade(context, effectiveArtistsText, layout.textX(), layout.artistY(), artistColor,
                        ARTIST_TEXT_SIZE, artistFadeW, artistFadeS);
            }
        }

        int progressTrackColor = Material3Colors.withAlpha(theme.surfaceVariant(), Math.round(180f * overlayAlpha));
        float filledWidth = layout.progressBarWidth() * Math.min(1.0f, Math.max(0.0f, displayProgress));
        try (RoundedRectBatch progressBatch = RoundedRectBatch.begin(context, 2)) {
            progressBatch.roundedRect(layout.progressBarLeft(), layout.progressBarY(),
                    layout.progressBarWidth(), PROGRESS_HEIGHT, PROGRESS_RADIUS, progressTrackColor);

            if (filledWidth > 0.5f) {
                int progressFillColor = Material3Colors.withAlpha(primaryColor, Math.round(245f * overlayAlpha));
                progressBatch.roundedRect(layout.progressBarLeft(), layout.progressBarY(),
                        filledWidth, PROGRESS_HEIGHT, PROGRESS_RADIUS, progressFillColor);
            }
        }

        int timeColor = Material3Colors.withAlpha(onSurfaceVariantColor, mutedTextAlpha);
        int iconMutedAlpha = Math.round(190f * overlayAlpha);

        float totalTimeActualW = SMALL_TEXT_RENDERER.width(totalTimeStr, TIME_TEXT_SIZE);
        float totalTimeDrawX = layout.progressBarRight() - totalTimeActualW;

        try (MsdfBatch batch = MsdfBatch.begin(context, 8)) {
            batch.drawText(SMALL_TEXT_RENDERER.getFont(), currentTimeStr,
                    layout.currentTimeX(), layout.timeY(), timeColor, TIME_TEXT_SIZE);
            batch.drawText(SMALL_TEXT_RENDERER.getFont(), totalTimeStr,
                    totalTimeDrawX, layout.timeY(), timeColor, TIME_TEXT_SIZE);
            batch.drawText(ICON_RENDERER.getFont(), "N", layout.prevX(), layout.controlsY(),
                    Material3Colors.withAlpha(onSurfaceVariantColor, iconMutedAlpha), ICON_SIZE);
            batch.drawText(ICON_RENDERER.getFont(), isPlaying ? "Q" : "O", layout.playX(), layout.controlsY(),
                    Material3Colors.withAlpha(primaryColor, textAlpha), ICON_SIZE);
            batch.drawText(ICON_RENDERER.getFont(), "P", layout.nextX(), layout.controlsY(),
                    Material3Colors.withAlpha(onSurfaceVariantColor, iconMutedAlpha), ICON_SIZE);

            renderHeaderAction(context, headerActions.status(), hoveredAction.id(), theme);
            renderHeaderAction(context, headerActions.sync(), hoveredAction.id(), theme);
            renderHeaderAction(context, headerActions.toggle(), hoveredAction.id(), theme);
        }

        if (lyricsRevealProgress > 0.02f) {
            int dividerAlpha = Math.round(90f * overlayAlpha * lyricsRevealProgress);
            RenderUtils.rect(context, x + CONTENT_PADDING, layout.dividerY(),
                    layout.boxWidth() - CONTENT_PADDING * 2f, DIVIDER_HEIGHT,
                    Material3Colors.withAlpha(theme.outlineVariant(), dividerAlpha));
        }

        if (layout.lyricsHeight() > 0f && lyricsRevealProgress > 0.02f) {
            renderLyrics(context, layout, deltaTime, theme);
        }
    }

    private static void renderHeaderAction(DrawContext context, TextAction action,
                                           String hoveredActionId, MediaTheme theme) {
        if (!action.visible()) {
            return;
        }

        boolean hovered = action.id().equals(hoveredActionId);
        float alphaFactor = action.clickable() ? (hovered ? 1.0f : 0.85f) : (hovered ? 0.8f : 0.65f);
        int color;
        if (action.clickable()) {
            color = Material3Colors.withAlpha(theme.primary(), Math.round(255f * overlayAlpha * alphaFactor));
        } else {
            color = Material3Colors.withAlpha(theme.onSurfaceVariant(), Math.round(220f * overlayAlpha * alphaFactor));
        }
        SMALL_TEXT_RENDERER.draw(context, action.label(), action.x(), action.y(), color, HEADER_ACTION_TEXT_SIZE);

        if (hovered && action.clickable()) {
            RenderUtils.rect(context, action.x(), action.y() + action.height() + 1f,
                    action.width(), HEADER_ACTION_UNDERLINE_HEIGHT,
                    Material3Colors.withAlpha(theme.primary(), Math.round(180f * overlayAlpha)));
        }
    }

    private static void renderLyrics(DrawContext context, MediaLayout layout,
                                     float deltaTime, MediaTheme theme) {
        float textX = layout.lyricsX() + LYRICS_TEXT_PADDING_X;
        float textY = resolveLyricsTextTop(layout);
        float textWidth = resolveLyricsTextWidth(layout);
        float textHeight = resolveLyricsTextHeight(layout);
        float fadeWidth = Math.min(14f, textWidth);

        if (textWidth <= 0f || textHeight <= 0f) {
            lyricsMaxScroll = 0f;
            return;
        }

        Lyrics current = lyrics;
        if (current == null || !current.isPresent()) {
            renderLyricsMessage(context, textX, textY, textWidth, textHeight, LYRICS_NOT_FOUND_TEXT, theme);
            return;
        }
        switch (current.kind()) {
            case SYNCED ->
                    renderSyncedLyrics(context, current, textX, textY, textWidth, textHeight, fadeWidth, deltaTime, theme);
            case PLAIN ->
                    renderPlainLyrics(context, current, textX, textY, textWidth, textHeight, fadeWidth, deltaTime, theme);
            default -> renderLyricsMessage(context, textX, textY, textWidth, textHeight, LYRICS_EMPTY_TEXT, theme);
        }
    }

    private static float resolveLyricsTextWidth(MediaLayout layout) {
        return Math.max(0f, layout.lyricsWidth() - LYRICS_TEXT_PADDING_X * 2f);
    }

    private static float resolveLyricsTextTop(MediaLayout layout) {
        return layout.lyricsY() + LYRICS_TEXT_PADDING_Y + (1f - lyricsRevealProgress) * LYRICS_REVEAL_SLIDE;
    }

    private static float resolveLyricsTextHeight(MediaLayout layout) {
        return Math.max(0f, layout.lyricsHeight() - LYRICS_TEXT_PADDING_Y * 2f - (1f - lyricsRevealProgress) * 2f);
    }

    private static void renderLyricsMessage(DrawContext context, float textX, float textY, float textWidth, float textHeight,
                                            String message, MediaTheme theme) {
        lyricsMaxScroll = 0f;
        String resolved = (message == null || message.isBlank()) ? LYRICS_EMPTY_TEXT : message;
        float messageWidth = Math.min(SMALL_TEXT_RENDERER.width(resolved, LYRICS_TEXT_SIZE), textWidth);
        float drawX = textX + Math.max(0f, (textWidth - messageWidth) / 2f);
        float drawY = textY + Math.max(0f, (textHeight - LYRICS_TEXT_SIZE) / 2f);
        SMALL_TEXT_RENDERER.drawWithWidthFade(context, resolved, drawX, drawY,
                Material3Colors.withAlpha(theme.onSurfaceVariant(), Math.round(190f * overlayAlpha * lyricsRevealProgress)),
                LYRICS_TEXT_SIZE, textWidth, Math.min(14f, textWidth));
    }

    private static void renderSyncedLyrics(DrawContext context, Lyrics payload,
                                           float textX, float textY, float textWidth, float textHeight,
                                           float fadeWidth, float deltaTime, MediaTheme theme) {
        List<LyricLine> sourceLines = payload.syncedLines();
        if (sourceLines.isEmpty()) {
            renderLyricsMessage(context, textX, textY, textWidth, textHeight, LYRICS_NOT_FOUND_TEXT, theme);
            return;
        }

        WrappedLyricsLayout wrappedLayout = getWrappedLyricsLayout(payload, textWidth);
        List<WrappedLyricLine> lines = wrappedLayout.lines();
        if (lines.isEmpty()) {
            renderLyricsMessage(context, textX, textY, textWidth, textHeight, LYRICS_NOT_FOUND_TEXT, theme);
            return;
        }

        boolean manualMode = syncedLyricsManualScrollActive;
        float contentTopY = textY;
        float contentHeight = textHeight;

        int activeSourceIndex = payload.activeLineIndex(currentTime);
        int activeVisualIndex = activeSourceIndex >= 0 && activeSourceIndex < wrappedLayout.firstVisualIndexBySource().length
                ? wrappedLayout.firstVisualIndexBySource()[activeSourceIndex]
                : -1;
        float totalContentHeight = Math.max(contentHeight, lines.size() * LYRICS_LINE_STEP);
        lyricsMaxScroll = Math.max(0f, totalContentHeight - contentHeight);
        float targetScroll = 0f;
        if (manualMode) {
            syncedLyricsManualScrollOffset = Math.max(0f, Math.min(syncedLyricsManualScrollOffset, lyricsMaxScroll));
            targetScroll = syncedLyricsManualScrollOffset;
        } else if (activeVisualIndex >= 0) {
            targetScroll = computeSyncedTargetScroll(activeVisualIndex, contentHeight, lines.size());
        }
        targetScroll = Math.max(0f, Math.min(targetScroll, lyricsMaxScroll));
        lyricsScrollOffset = UiAnimationManager.animateTextScroll(lyricsScrollOffset, targetScroll, deltaTime);

        if (!enableScissor(context, textX, contentTopY, textWidth, contentHeight)) {
            return;
        }

        try {
            for (int i = 0; i < lines.size(); i++) {
                float lineY = contentTopY + i * LYRICS_LINE_STEP - lyricsScrollOffset;
                if (lineY < contentTopY - LYRICS_LINE_STEP || lineY > contentTopY + contentHeight + 2f) {
                    continue;
                }

                WrappedLyricLine line = lines.get(i);
                float alphaFactor = computeSyncedLineAlphaFactor(line.sourceIndex(), activeSourceIndex, manualMode)
                        * computeViewportFade(lineY, contentTopY, contentHeight)
                        * lyricsRevealProgress;
                if (alphaFactor <= 0.02f) {
                    continue;
                }

                String drawText = line.blank() ? " " : line.text();
                if (!manualMode && line.sourceIndex() == activeSourceIndex && !theme.coverBased()) {
                    SMALL_TEXT_RENDERER.drawThemeGradientWithWidthFade(context, drawText, textX, lineY, LYRICS_TEXT_SIZE, 90,
                            Math.max(0f, Math.min(1f, overlayAlpha * alphaFactor)),
                            textWidth, fadeWidth);
                } else {
                    int lyricColor = !manualMode && line.sourceIndex() == activeSourceIndex
                            ? theme.primary()
                            : theme.onSurfaceVariant();
                    SMALL_TEXT_RENDERER.drawWithWidthFade(context, drawText, textX, lineY,
                            Material3Colors.withAlpha(lyricColor, Math.round(255f * overlayAlpha * alphaFactor)),
                            LYRICS_TEXT_SIZE, textWidth, fadeWidth);
                }
            }
        } finally {
            context.disableScissor();
        }
    }

    private static void renderPlainLyrics(DrawContext context, Lyrics payload,
                                          float textX, float textY, float textWidth, float textHeight,
                                          float fadeWidth, float deltaTime, MediaTheme theme) {
        if (payload.plainLines().isEmpty()) {
            renderLyricsMessage(context, textX, textY, textWidth, textHeight, LYRICS_EMPTY_TEXT, theme);
            return;
        }

        WrappedLyricsLayout wrappedLayout = getWrappedLyricsLayout(payload, textWidth);
        List<WrappedLyricLine> lines = wrappedLayout.lines();
        if (lines.isEmpty()) {
            renderLyricsMessage(context, textX, textY, textWidth, textHeight, LYRICS_EMPTY_TEXT, theme);
            return;
        }

        float contentHeight = Math.max(textHeight, lines.size() * LYRICS_LINE_STEP);
        lyricsMaxScroll = Math.max(0f, contentHeight - textHeight);

        lyricsManualScrollOffset = Math.max(0f, Math.min(lyricsManualScrollOffset, lyricsMaxScroll));
        float targetScroll = lyricsManualScrollActive ? lyricsManualScrollOffset : 0f;
        lyricsScrollOffset = UiAnimationManager.animateTextScroll(lyricsScrollOffset, targetScroll, deltaTime);

        if (!enableScissor(context, textX, textY, textWidth, textHeight)) {
            return;
        }

        try {
            for (int i = 0; i < lines.size(); i++) {
                float lineY = textY + i * LYRICS_LINE_STEP - lyricsScrollOffset;
                if (lineY < textY - LYRICS_LINE_STEP || lineY > textY + textHeight + 2f) {
                    continue;
                }

                WrappedLyricLine line = lines.get(i);
                String lineText = line.blank() ? " " : line.text();
                float alphaFactor = (line.blank() ? 0.3f : 0.8f) * computeViewportFade(lineY, textY, textHeight) * lyricsRevealProgress;
                if (alphaFactor <= 0.02f) {
                    continue;
                }

                SMALL_TEXT_RENDERER.drawWithWidthFade(context, lineText, textX, lineY,
                        Material3Colors.withAlpha(theme.onSurfaceVariant(), Math.round(255f * overlayAlpha * alphaFactor)),
                        LYRICS_TEXT_SIZE, textWidth, fadeWidth);
            }
        } finally {
            context.disableScissor();
        }
    }

    private static float computeSyncedLineAlphaFactor(int sourceIndex, int activeSourceIndex, boolean manualMode) {
        if (manualMode) {
            return 0.74f;
        }
        if (activeSourceIndex < 0) {
            return sourceIndex == 0 ? 0.58f : 0.3f;
        }

        int distance = Math.abs(sourceIndex - activeSourceIndex);
        if (distance == 0) {
            return 0.94f;
        }
        return Math.max(0.18f, 0.76f - distance * 0.16f);
    }

    private static float computeViewportFade(float lineY, float viewTop, float viewHeight) {
        float centerY = lineY + LYRICS_LINE_STEP * 0.5f;
        float fadeZone = Math.min(18f, viewHeight * 0.2f);
        float viewBottom = viewTop + viewHeight;

        if (centerY < viewTop + fadeZone) {
            return Math.max(0f, (centerY - viewTop) / Math.max(1f, fadeZone));
        }
        if (centerY > viewBottom - fadeZone) {
            return Math.max(0f, (viewBottom - centerY) / Math.max(1f, fadeZone));
        }
        return 1f;
    }

    private static boolean enableScissor(DrawContext context, float x, float y, float width, float height) {
        int left = (int) Math.floor(x);
        int top = (int) Math.floor(y);
        int right = (int) Math.ceil(x + width);
        int bottom = (int) Math.ceil(y + height);
        if (right <= left || bottom <= top) {
            return false;
        }

        context.enableScissor(left, top, right, bottom);
        return true;
    }

    private static boolean isPointInsideTimeline(MediaLayout layout, float mouseX, float mouseY) {
        float top = layout.progressBarY() - 3.5f;
        float bottom = layout.progressBarY() + PROGRESS_HEIGHT + 2f;
        return mouseX >= layout.progressBarLeft() - 2f
                && mouseX <= layout.progressBarRight() + 2f
                && mouseY >= top
                && mouseY <= bottom;
    }

    private static long computeTimelineSeekMs(MediaLayout layout, float mouseX) {
        float progressValue = (mouseX - layout.progressBarLeft()) / Math.max(1f, layout.progressBarWidth());
        progressValue = Math.max(0f, Math.min(1f, progressValue));
        return Math.round(progressValue * Math.max(1L, totalTime));
    }

    private static void previewTimelineSeek(MediaLayout layout, float mouseX) {
        long previewMs = computeTimelineSeekMs(layout, mouseX);
        timelineScrubPreviewMs = previewMs;
        currentTime = previewMs;
        progress = Math.min(1.0f, Math.max(0.0f, totalTime <= 0L ? 0f : (float) previewMs / (float) totalTime));
        displayProgress = progress;
    }

    private static void commitTimelineSeek() {
        if (timelineScrubPreviewMs < 0L) {
            return;
        }
        performSeek(timelineScrubPreviewMs);
        timelineScrubPreviewMs = -1L;
    }

    private static float computeSyncedTargetScroll(int index, float viewHeight, int lineCount) {
        if (index < 0 || lineCount <= 0) {
            return 0f;
        }
        float contentHeight = Math.max(viewHeight, lineCount * LYRICS_LINE_STEP);
        float maxScroll = Math.max(0f, contentHeight - viewHeight);
        float targetScroll = index * LYRICS_LINE_STEP - viewHeight / 2f + LYRICS_LINE_STEP / 2f;
        return Math.max(0f, Math.min(targetScroll, maxScroll));
    }

    private static int findSyncedLyricIndexAtPosition(MediaLayout layout, float mouseY) {
        if (!isLyricsPanelExpanded() || !isSyncedLyrics()) {
            return -1;
        }

        WrappedLyricsLayout wrappedLayout = getWrappedLyricsLayout(lyrics, resolveLyricsTextWidth(layout));
        List<WrappedLyricLine> lines = wrappedLayout.lines();
        if (lines.isEmpty()) {
            return -1;
        }

        float textY = resolveLyricsTextTop(layout);
        float textHeight = resolveLyricsTextHeight(layout);
        if (mouseY < textY || mouseY > textY + textHeight) {
            return -1;
        }

        for (int i = 0; i < lines.size(); i++) {
            float lineY = textY + i * LYRICS_LINE_STEP - lyricsScrollOffset;
            if (mouseY >= lineY - 2f && mouseY <= lineY + LYRICS_LINE_STEP) {
                return lines.get(i).sourceIndex();
            }
        }

        return -1;
    }

    private static void seekToSyncedLyricIndex(int index, MediaLayout layout) {
        Lyrics current = lyrics;
        if (current == null || current.kind() != Lyrics.Kind.SYNCED) {
            return;
        }
        List<LyricLine> lines = current.syncedLines();
        if (index < 0 || index >= lines.size()) {
            return;
        }
        WrappedLyricsLayout wrappedLayout = getWrappedLyricsLayout(current, resolveLyricsTextWidth(layout));
        syncedLyricsManualScrollActive = false;
        syncedLyricsManualScrollOffset = 0f;
        float viewHeight = resolveLyricsTextHeight(layout);
        int visualIndex = index < wrappedLayout.firstVisualIndexBySource().length
                ? wrappedLayout.firstVisualIndexBySource()[index]
                : index;
        lyricsScrollOffset = computeSyncedTargetScroll(visualIndex, viewHeight, wrappedLayout.lines().size());
        performSeek(lines.get(index).timeMs());
    }

    private static void performSeek(long targetMs) {
        long clampedTarget = Math.max(0L, Math.min(targetMs, Math.max(0L, totalTime)));
        currentTime = clampedTarget;
        progress = Math.min(1.0f, Math.max(0.0f, totalTime <= 0L ? 0f : (float) clampedTarget / (float) totalTime));
        displayProgress = progress;
        submitCommand(() -> {
            MediaSession s = session;
            if (s != null) {
                s.seek(clampedTarget);
            }
        });
    }

    private static void renderPlaceholderCover(DrawContext context, float x, float y, float size, float alpha, MediaTheme theme) {
        int placeholderAlpha = Math.round(140f * Math.max(0f, Math.min(1f, alpha)));
        RenderUtils.roundedRect(context, x, y, size, size, COVER_RADIUS,
                Material3Colors.withAlpha(theme.surfaceVariant(), placeholderAlpha));
    }

    public static boolean handleClick(float mouseX, float mouseY, float x, float y, float w, float h) {
        if (shuttingDown.get() || session == null || trackName == null || trackName.isEmpty()
                || !visualActive || overlayAlpha <= 0.05f)
            return false;
        updatePointerState(mouseX, mouseY);
        if (!(mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h))
            return false;

        MediaLayout layout = createLayout(x, y);
        TextAction headerAction = findHeaderActionAt(x, layout, mouseX, mouseY);
        if (headerAction.visible() && headerAction.clickable()) {
            switch (headerAction.id()) {
                case "toggle" -> lyricsCollapsed = !lyricsCollapsed;
                case "sync" -> {
                    syncedLyricsManualScrollActive = false;
                    syncedLyricsManualScrollOffset = 0f;
                }
                case "status" -> forceRetryLyricsRequest();
                default -> {
                    return false;
                }
            }
            timelineScrubbing = false;
            return true;
        }

        float hitTop = layout.controlsY() - 3.5f;
        float hitBottom = layout.controlsY() + ICON_SIZE + 3.5f;

        if (mouseY >= hitTop && mouseY <= hitBottom) {
            float tolerance = 3f;

            if (mouseX >= layout.prevX() - tolerance && mouseX <= layout.prevX() + layout.prevW() + tolerance) {
                submitCommand(() -> {
                    MediaSession s = session;
                    if (s != null) {
                        s.skipPrevious();
                    }
                });
                return true;
            }

            float playHitLeft = layout.prevX() + layout.prevW() + tolerance;
            float playHitRight = layout.nextX() - tolerance;
            if (mouseX >= playHitLeft && mouseX <= playHitRight) {
                submitCommand(() -> {
                    MediaSession s = session;
                    if (s != null) {
                        s.togglePlayPause();
                    }
                });
                return true;
            }

            if (mouseX >= layout.nextX() - tolerance && mouseX <= layout.nextX() + layout.nextW() + tolerance) {
                submitCommand(() -> {
                    MediaSession s = session;
                    if (s != null) {
                        s.skipNext();
                    }
                });
                return true;
            }
        }

        if (isPointInsideTimeline(layout, mouseX, mouseY)) {
            timelineScrubbing = true;
            previewTimelineSeek(layout, mouseX);
            return true;
        }

        if (isSyncedLyrics() && isLyricsPanelExpanded()) {
            int lyricIndex = findSyncedLyricIndexAtPosition(layout, mouseY);
            if (lyricIndex >= 0 && mouseX >= layout.lyricsX() && mouseX <= layout.lyricsX() + layout.lyricsWidth()) {
                timelineScrubbing = false;
                seekToSyncedLyricIndex(lyricIndex, layout);
                return true;
            }
        }

        return false;
    }

    public static boolean handlePointerMove(float mouseX, float mouseY, float x, float y) {
        updatePointerState(mouseX, mouseY);
        if (!timelineScrubbing) {
            return false;
        }

        MediaLayout layout = createLayout(x, y);
        previewTimelineSeek(layout, mouseX);
        return true;
    }

    public static void handleMouseRelease() {
        if (timelineScrubbing) {
            commitTimelineSeek();
        }
        timelineScrubbing = false;
    }

    public static boolean handleScroll(float mouseX, float mouseY, float x, float y, double verticalAmount) {
        if (overlayAlpha <= 0.05f || !isLyricsPanelExpanded()) {
            return false;
        }

        updatePointerState(mouseX, mouseY);
        MediaLayout layout = createLayout(x, y);
        float lyricsX = layout.lyricsX();
        float lyricsY = layout.lyricsY();
        float lyricsW = layout.lyricsWidth();
        float lyricsH = layout.lyricsHeight();

        if (!(mouseX >= lyricsX && mouseX <= lyricsX + lyricsW && mouseY >= lyricsY && mouseY <= lyricsY + lyricsH)) {
            return false;
        }

        if (isSyncedLyrics()) {
            if (lyricsMaxScroll <= 0f) {
                return false;
            }
            float baseOffset = syncedLyricsManualScrollActive ? syncedLyricsManualScrollOffset : lyricsScrollOffset;
            syncedLyricsManualScrollOffset = Math.max(0f, Math.min(baseOffset - (float) verticalAmount * LYRICS_SCROLL_STEP, lyricsMaxScroll));
            syncedLyricsManualScrollActive = true;
            return true;
        }

        Lyrics current = lyrics;
        if (current == null || current.kind() != Lyrics.Kind.PLAIN || lyricsMaxScroll <= 0f) {
            return false;
        }

        float baseOffset = lyricsManualScrollActive ? lyricsManualScrollOffset : lyricsScrollOffset;
        lyricsManualScrollOffset = Math.max(0f, Math.min(baseOffset - (float) verticalAmount * LYRICS_SCROLL_STEP, lyricsMaxScroll));
        lyricsManualScrollActive = true;
        return true;
    }

    private static void submitCommand(Runnable command) {
        if (shuttingDown.get()) {
            return;
        }
        try {
            commandExecutor.execute(command);
        } catch (RejectedExecutionException ignored) {
        }
    }

    public static void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return;
        }

        MediaSession s = session;
        session = null;
        if (s != null) {
            try {
                s.removeListener(LISTENER);
            } catch (Throwable ignored) {
            }
            try {
                s.close();
            } catch (Throwable t) {
                rethrowIfFatal(t);
                LOGGER.log(Level.FINE, "[MediaPlayerRenderer] Session close failed", t);
            }
        }

        try {
            imageDecoder.shutdownNow();
            commandExecutor.shutdownNow();
        } catch (Throwable ignored) {
        }

        pendingAlbumArt.set(null);
        imageDecoding.set(false);
        clearData();

        try {
            if (coverTexture != null) {
                coverTexture.close();
                coverTexture = null;
            }
            if (previousCoverTexture != null) {
                previousCoverTexture.close();
                previousCoverTexture = null;
            }
        } catch (Throwable ignored) {
        }
    }
}
