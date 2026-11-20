package kz.regullar.fastmedia;

/**
 * A process that owns an audio render session and can be targeted by the waveform capture.
 *
 * @param pid        process id, pass it to {@link MediaSession#setWaveformSource(int)}
 * @param name       executable base name, e.g. {@code Spotify.exe}
 * @param peak       session peak meter 0..1 at enumeration time
 * @param active     the session was rendering audio at enumeration time
 * @param nowPlaying this process owns the current SMTC media session
 */
public record AudioSource(int pid, String name, float peak, boolean active, boolean nowPlaying) {
}
