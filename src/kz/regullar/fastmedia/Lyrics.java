package kz.regullar.fastmedia;

import java.util.List;
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

    public static Lyrics synced(List<LyricLine> lines) {
        return new Lyrics(Kind.SYNCED, List.copyOf(lines), List.of(), true);
    }

    public static Lyrics plain(List<String> lines) {
        return new Lyrics(Kind.PLAIN, List.of(), List.copyOf(lines), true);
    }
    public static Lyrics notFound() {
        return NOT_FOUND;
    }
    public static Lyrics unavailable() {
        return UNAVAILABLE;
    }

    public Kind kind() {
        return kind;
    }
    public boolean isPresent() {
        return kind != Kind.NONE;
    }
    public boolean available() {
        return isPresent() || conclusive;
    }

    public List<LyricLine> syncedLines() {
        return syncedLines;
    }

    public List<String> plainLines() {
        return plainLines;
    }
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
