package kz.regullar.fastmedia;

import java.time.Duration;

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

    public String source() {
        return source;
    }

    public byte[] albumArt() {
        return albumArt;
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

    public boolean trackChanged() {
        return trackChanged;
    }

    @Override
    public String toString() {
        return "NowPlaying[" + title + " - " + artist + " (" + source + "), "
                + (playing ? "playing" : "paused") + ", " + positionMs + "/" + durationMs + "ms]";
    }
}
