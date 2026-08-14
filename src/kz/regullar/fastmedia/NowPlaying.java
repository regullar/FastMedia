package kz.regullar.fastmedia;

import java.time.Duration;
import java.util.Arrays;

/**
 * Immutable snapshot of the currently playing media session.
 *
 * <p>{@link #albumArt()} returns the raw PNG/JPEG bytes of the cover, or
 * {@code null} when no art is available. Defensive copies are made so the
 * snapshot is safe to share across threads.
 */
public final class NowPlaying {
    private final String title;
    private final String artist;
    private final String source;
    private final byte[] albumArt;
    private final boolean playing;
    private final long durationMs;
    private final long positionMs;
    private final boolean trackChanged;

    NowPlaying(String title, String artist, String source, byte[] albumArt,
               boolean playing, long durationMs, long positionMs, boolean trackChanged) {
        this.title = title;
        this.artist = artist;
        this.source = source;
        this.albumArt = albumArt;
        this.playing = playing;
        this.durationMs = durationMs;
        this.positionMs = positionMs;
        this.trackChanged = trackChanged;
    }

    public String title() {
        return title;
    }

    public String artist() {
        return artist;
    }

    /** The app the media originates from (e.g. {@code Spotify}). */
    public String source() {
        return source;
    }

    /** Raw cover image bytes, or {@code null}. A fresh copy is returned each call. */
    public byte[] albumArt() {
        return albumArt == null ? null : Arrays.copyOf(albumArt, albumArt.length);
    }

    public boolean hasAlbumArt() {
        return albumArt != null && albumArt.length > 0;
    }

    public boolean isPlaying() {
        return playing;
    }

    public Duration duration() {
        return Duration.ofMillis(durationMs);
    }

    public Duration position() {
        return Duration.ofMillis(positionMs);
    }

    public long durationMs() {
        return durationMs;
    }

    public long positionMs() {
        return positionMs;
    }

    /** {@code true} when the track or its cover changed since the previous query. */
    public boolean trackChanged() {
        return trackChanged;
    }

    @Override
    public String toString() {
        return "NowPlaying[" + title + " - " + artist + " (" + source + "), "
                + (playing ? "playing" : "paused") + ", " + positionMs + "/" + durationMs + "ms]";
    }
}
