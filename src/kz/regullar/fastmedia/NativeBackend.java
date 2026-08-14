package kz.regullar.fastmedia;

import com.sun.jna.Native;
import kz.regullar.fastmedia.internal.LyricsParser;
import kz.regullar.fastmedia.internal.LyricsResultStruct;
import kz.regullar.fastmedia.internal.MediaInfoStruct;
import kz.regullar.fastmedia.internal.FastMediaNative;
import kz.regullar.fastmedia.internal.PositionInfoStruct;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads {@code FastMedia.dll} and exposes typed, thread-safe access to it.
 *
 * <p>The native layer is internally synchronised, so there is no process-wide
 * Java lock here: independent calls (metadata, position, transport, waveform)
 * run concurrently. Per-thread struct buffers avoid repeated native allocation.
 */
final class NativeBackend {
    private static final Logger LOG = Logger.getLogger(NativeBackend.class.getName());
    private static final String DLL_RESOURCE = "/FastMedia.dll";

    private static final ThreadLocal<MediaInfoStruct> MEDIA_BUF =
            ThreadLocal.withInitial(MediaInfoStruct::new);
    private static final ThreadLocal<PositionInfoStruct> POSITION_BUF =
            ThreadLocal.withInitial(PositionInfoStruct::new);

    private static volatile boolean initialized = false;

    private NativeBackend() {
    }

    static synchronized void load() {
        if (initialized) {
            return;
        }
        try (InputStream in = NativeBackend.class.getResourceAsStream(DLL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("FastMedia.dll not found on classpath (" + DLL_RESOURCE + ")");
            }
            Path tmp = Files.createTempFile("FastMedia", ".dll");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            FastMediaNative.register(tmp.toAbsolutePath().toString());
            validateLayout();
            initialized = true;
            LOG.log(Level.INFO, "[FastMedia] native library loaded from {0} (JNA Direct Mapping)", tmp);
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("Failed to load FastMedia.dll: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract FastMedia.dll from resources", e);
        }
    }

    private static void ensureLoaded() {
        if (!initialized) {
            load();
        }
    }

    private static void validateLayout() {
        int pointer = Native.POINTER_SIZE;
        int expectedMedia = pointer == 8 ? 1310 : 1306;
        int actualMedia = new MediaInfoStruct().size();
        if (actualMedia != expectedMedia) {
            throw new IllegalStateException(
                    "MediaInfo layout mismatch: expected " + expectedMedia + ", got " + actualMedia);
        }
        int actualPosition = new PositionInfoStruct().size();
        if (actualPosition != 17) {
            throw new IllegalStateException(
                    "PositionInfo layout mismatch: expected 17, got " + actualPosition);
        }
    }

    // --- Push Events (WinRT) ---

    static boolean registerEventCallback(FastMediaNative.MediaEventCallback callback) {
        ensureLoaded();
        return FastMediaNative.RegisterMediaEventCallback(callback) != 0;
    }

    static void unregisterEventCallback() {
        if (FastMediaNative.isRegistered()) {
            FastMediaNative.UnregisterMediaEventCallback();
        }
    }

    // --- Metadata / position ---

    static NowPlaying queryNowPlaying() {
        ensureLoaded();
        MediaInfoStruct s = MEDIA_BUF.get();
        if (FastMediaNative.GetCurrentMediaInfo(s) == 0) {
            return null;
        }
        try {
            boolean trackChanged = s.trackChanged();
            byte[] art = trackChanged ? s.albumArt() : null;
            return new NowPlaying(s.title(), s.artist(), s.source(), art,
                    s.playing(), s.durationMs, s.positionMs, trackChanged);
        } finally {
            FastMediaNative.FreeMediaInfo(s);
        }
    }

    static PlaybackPosition queryPosition() {
        ensureLoaded();
        PositionInfoStruct s = POSITION_BUF.get();
        if (FastMediaNative.GetCurrentPositionInfo(s) == 0) {
            return null;
        }
        return new PlaybackPosition(s.playing(), s.durationMs, s.positionMs);
    }

    static void clearCache() {
        ensureLoaded();
        FastMediaNative.ClearCache();
    }

    // --- Transport ---

    static boolean play() {
        ensureLoaded();
        return FastMediaNative.Play() != 0;
    }

    static boolean pause() {
        ensureLoaded();
        return FastMediaNative.Pause() != 0;
    }

    static boolean toggle() {
        ensureLoaded();
        return FastMediaNative.TogglePlayPause() != 0;
    }

    static boolean next() {
        ensureLoaded();
        return FastMediaNative.SkipNext() != 0;
    }

    static boolean previous() {
        ensureLoaded();
        return FastMediaNative.SkipPrevious() != 0;
    }

    static boolean seek(long ms) {
        ensureLoaded();
        return FastMediaNative.SeekToMs(ms) != 0;
    }

    // --- Waveform ---

    static boolean startAudioCapture() {
        ensureLoaded();
        return FastMediaNative.StartAudioCapture() != 0;
    }

    static void stopAudioCapture() {
        if (FastMediaNative.isRegistered()) {
            FastMediaNative.StopAudioCapture();
        }
    }

    static int waveform(float[] out) {
        ensureLoaded();
        return FastMediaNative.GetWaveform(out, out.length);
    }

    static float audioLevel() {
        ensureLoaded();
        return FastMediaNative.GetAudioLevel();
    }

    // --- Lyrics ---

    static Lyrics fetchLyrics(String artist, String title) {
        ensureLoaded();
        LyricsResultStruct result = new LyricsResultStruct();
        int status = FastMediaNative.FetchLyrics(utf8(artist), utf8(title), result);
        result.read();
        try {
            if (status != 0) {
                // 1 = not found (conclusive), 2 = transient error.
                return status == 1 ? Lyrics.notFound() : Lyrics.unavailable();
            }
            List<LyricLine> synced = LyricsParser.parseSynced(result.syncedLrc());
            if (!synced.isEmpty()) {
                return Lyrics.synced(synced);
            }
            List<String> plain = LyricsParser.parsePlain(result.plainText());
            return plain.isEmpty() ? Lyrics.notFound() : Lyrics.plain(plain);
        } finally {
            FastMediaNative.FreeLyrics(result);
        }
    }

    private static byte[] utf8(String s) {
        byte[] raw = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        byte[] terminated = new byte[raw.length + 1];
        System.arraycopy(raw, 0, terminated, 0, raw.length);
        return terminated;
    }
}
