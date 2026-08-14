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

    private static volatile FastMediaNative lib;

    private NativeBackend() {
    }

    static synchronized void load() {
        if (lib != null) {
            return;
        }
        try (InputStream in = NativeBackend.class.getResourceAsStream(DLL_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("FastMedia.dll not found on classpath (" + DLL_RESOURCE + ")");
            }
            Path tmp = Files.createTempFile("FastMedia", ".dll");
            tmp.toFile().deleteOnExit();
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            FastMediaNative loaded = Native.load(tmp.toAbsolutePath().toString(), FastMediaNative.class);
            validateLayout();
            lib = loaded;
            LOG.log(Level.INFO, "[FastMedia] native library loaded from {0}", tmp);
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException("Failed to load FastMedia.dll: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to extract FastMedia.dll from resources", e);
        }
    }

    private static FastMediaNative lib() {
        FastMediaNative local = lib;
        if (local == null) {
            load();
            local = lib;
        }
        if (local == null) {
            throw new IllegalStateException("FastMedia native library is not initialised");
        }
        return local;
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

    // --- Metadata / position ---

    static NowPlaying queryNowPlaying() {
        FastMediaNative l = lib();
        MediaInfoStruct s = MEDIA_BUF.get();
        if (l.GetCurrentMediaInfo(s) == 0) {
            return null;
        }
        try {
            boolean trackChanged = s.trackChanged();
            byte[] art = trackChanged ? s.albumArt() : null;
            return new NowPlaying(s.title(), s.artist(), s.source(), art,
                    s.playing(), s.durationMs, s.positionMs, trackChanged);
        } finally {
            l.FreeMediaInfo(s);
        }
    }

    static PlaybackPosition queryPosition() {
        FastMediaNative l = lib();
        PositionInfoStruct s = POSITION_BUF.get();
        if (l.GetCurrentPositionInfo(s) == 0) {
            return null;
        }
        return new PlaybackPosition(s.playing(), s.durationMs, s.positionMs);
    }

    static void clearCache() {
        lib().ClearCache();
    }

    // --- Transport ---

    static boolean play() {
        return lib().Play() != 0;
    }

    static boolean pause() {
        return lib().Pause() != 0;
    }

    static boolean toggle() {
        return lib().TogglePlayPause() != 0;
    }

    static boolean next() {
        return lib().SkipNext() != 0;
    }

    static boolean previous() {
        return lib().SkipPrevious() != 0;
    }

    static boolean seek(long ms) {
        return lib().SeekToMs(ms) != 0;
    }

    // --- Waveform ---

    static boolean startAudioCapture() {
        return lib().StartAudioCapture() != 0;
    }

    static void stopAudioCapture() {
        lib().StopAudioCapture();
    }

    static int waveform(float[] out) {
        return lib().GetWaveform(out, out.length);
    }

    static float audioLevel() {
        return lib().GetAudioLevel();
    }

    // --- Lyrics ---

    static Lyrics fetchLyrics(String artist, String title) {
        FastMediaNative l = lib();
        LyricsResultStruct result = new LyricsResultStruct();
        int status = l.FetchLyrics(utf8(artist), utf8(title), result);
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
            l.FreeLyrics(result);
        }
    }

    private static byte[] utf8(String s) {
        byte[] raw = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        byte[] terminated = new byte[raw.length + 1];
        System.arraycopy(raw, 0, terminated, 0, raw.length);
        return terminated;
    }
}
