package kz.regullar.fastmedia;

/** A single time-synchronised lyric line. {@code timeMs} is the start offset. */
public record LyricLine(long timeMs, String text) {
}
