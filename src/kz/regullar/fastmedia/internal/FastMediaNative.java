package kz.regullar.fastmedia.internal;

import com.sun.jna.win32.StdCallLibrary;

/**
 * Direct 1:1 binding to the exported functions of {@code FastMedia.dll}.
 *
 * <p>This interface is internal. Application code uses
 * {@link kz.regullar.fastmedia.MediaSession} instead.
 */
public interface FastMediaNative extends StdCallLibrary {

    // --- Media metadata / position ---
    int GetCurrentMediaInfo(MediaInfoStruct info);

    int GetCurrentPositionInfo(PositionInfoStruct info);

    void FreeMediaInfo(MediaInfoStruct info);

    void ClearCache();

    // --- Transport ---
    int Play();

    int Pause();

    int TogglePlayPause();

    int SkipNext();

    int SkipPrevious();

    int SeekToMs(long ms);

    // --- Audio waveform (WASAPI loopback) ---
    int StartAudioCapture();

    void StopAudioCapture();

    int GetWaveform(float[] outBuffer, int maxSamples);

    float GetAudioLevel();

    // --- Lyrics ---
    int FetchLyrics(byte[] artistUtf8, byte[] titleUtf8, LyricsResultStruct out);

    void FreeLyrics(LyricsResultStruct result);
}
