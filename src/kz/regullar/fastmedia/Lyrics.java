package kz.regullar.fastmedia;

import java.util.List;

/**
 * Result of a lyrics lookup.
 *
 * <p>A lookup yields one of three kinds:
 * <ul>
 *   <li>{@link Kind#SYNCED} — time-synchronised lines (see {@link #syncedLines()});</li>
 *   <li>{@link Kind#PLAIN} — plain text lines (see {@link #plainLines()});</li>
 *   <li>{@link Kind#NONE} — no lyrics were found, or the lookup failed transiently
 *       (distinguished by {@link #available()}).</li>
 * </ul>
 */
public final class Lyrics {

    public enum Kind {SYNCED, PLAIN, NONE}

    private static final Lyrics NOT_FOUND = new Lyrics(Kind.NONE, List.of(), List.of(), true);
    private static final Lyrics UNAVAILABLE = new Lyrics(Kind.NONE, List.of(), List.of(), false);

    private final Kind kind;
    private final List<LyricLine> syncedLines;
    private final List<String> plainLines;
    private final boolean conclusive;

    private Lyrics(Kind kind, List<LyricLine> syncedLines, List<String> plainLines, boolean conclusive) {
        this.kind = kind;
        this.syncedLines = syncedLines;
        this.plainLines = plainLines;
        this.conclusive = conclusive;
    }

    static Lyrics synced(List<LyricLine> lines) {
        return new Lyrics(Kind.SYNCED, List.copyOf(lines), List.of(), true);
    }

    static Lyrics plain(List<String> lines) {
        return new Lyrics(Kind.PLAIN, List.of(), List.copyOf(lines), true);
    }

    /** Lyrics definitively do not exist (negative result, safe to cache). */
    static Lyrics notFound() {
        return NOT_FOUND;
    }

    /** Lookup failed transiently (network/parse); retry later. */
    static Lyrics unavailable() {
        return UNAVAILABLE;
    }

    public Kind kind() {
        return kind;
    }

    /** {@code true} when synced or plain lyrics are present. */
    public boolean isPresent() {
        return kind != Kind.NONE;
    }

    /**
     * {@code true} when the result is conclusive: either lyrics were found or
     * they definitively do not exist. {@code false} signals a transient failure
     * that callers may retry.
     */
    public boolean available() {
        return isPresent() || conclusive;
    }

    public List<LyricLine> syncedLines() {
        return syncedLines;
    }

    public List<String> plainLines() {
        return plainLines;
    }

    /**
     * Index of the synced line active at {@code positionMs}, or {@code -1} when
     * there are no synced lines or playback is before the first timestamp.
     */
    public int activeLineIndex(long positionMs) {
        if (kind != Kind.SYNCED || syncedLines.isEmpty()) {
            return -1;
        }
        int lo = 0, hi = syncedLines.size() - 1, result = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (syncedLines.get(mid).timeMs() <= positionMs) {
                result = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return result;
    }
}
