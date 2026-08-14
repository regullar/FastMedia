package kz.regullar.fastmedia.internal;

import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;

/**
 * Direct 1:1 JNA Direct Mapping binding to the exported functions of {@code FastMedia.dll}.
 *
 * <p>Direct Mapping eliminates dynamic invocation handlers and proxy reflection overhead,
 * routing calls directly through generated JNI stubs for maximum throughput.
 *
 * <p>This class is internal. Application code uses
 * {@link kz.regullar.fastmedia.MediaSession} instead.
 */
public final class FastMediaNative {

    public interface MediaEventCallback extends Callback {
        void invoke(int eventType);
    }

    public static final int EVENT_NONE = 0;
    public static final int EVENT_MEDIA_PROPERTIES_CHANGED = 1;
    public static final int EVENT_PLAYBACK_INFO_CHANGED = 2;
    public static final int EVENT_TIMELINE_PROPERTIES_CHANGED = 3;
    public static final int EVENT_SESSION_CHANGED = 4;
    public static final int EVENT_PLAYBACK_STOPPED = 5;

    private static volatile boolean registered = false;

    private FastMediaNative() {
    }

    public static synchronized void register(String libraryPath) {
        if (registered) {
            return;
        }
        Native.register(FastMediaNative.class, NativeLibrary.getInstance(libraryPath));
        registered = true;
    }

    public static synchronized void unregister() {
        if (!registered) {
            return;
        }
        Native.unregister(FastMediaNative.class);
        registered = false;
    }

    public static boolean isRegistered() {
        return registered;
    }

    // --- Media metadata / position ---
    public static native int GetCurrentMediaInfo(MediaInfoStruct info);

    public static native int GetCurrentPositionInfo(PositionInfoStruct info);

    public static native void FreeMediaInfo(MediaInfoStruct info);

    public static native void ClearCache();

    // --- Push Events (WinRT) ---
    public static native int RegisterMediaEventCallback(MediaEventCallback callback);

    public static native void UnregisterMediaEventCallback();

    // --- Transport ---
    public static native int Play();

    public static native int Pause();

    public static native int TogglePlayPause();

    public static native int SkipNext();

    public static native int SkipPrevious();

    public static native int SeekToMs(long ms);

    // --- Audio waveform (WASAPI loopback) ---
    public static native int StartAudioCapture();

    public static native void StopAudioCapture();

    public static native int GetWaveform(float[] outBuffer, int maxSamples);

    public static native float GetAudioLevel();

    // --- Lyrics ---
    public static native int FetchLyrics(byte[] artistUtf8, byte[] titleUtf8, LyricsResultStruct out);

    public static native void FreeLyrics(LyricsResultStruct result);
}

