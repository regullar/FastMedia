package kz.regullar.fastmedia.internal;

import kz.regullar.fastmedia.LyricLine;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns raw lrclib payloads (LRC text / plain text) into structured lines.
 *
 * <p>This is the only lyrics text processing left in the Java layer; the actual
 * HTTP retrieval and the lrclib JSON live entirely in the native DLL.
 */
public final class LyricsParser {

    private static final Pattern LRC_TIME =
            Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]");

    private LyricsParser() {
    }

    /** Parses LRC text into time-sorted lines; empty when none parse. */
    public static List<LyricLine> parseSynced(String lrc) {
        if (lrc == null || lrc.isBlank()) {
            return List.of();
        }
        List<LyricLine> lines = new ArrayList<>();
        for (String raw : lrc.split("\\R", -1)) {
            Matcher m = LRC_TIME.matcher(raw);
            int lastEnd = -1;
            List<Long> times = new ArrayList<>(2);
            while (m.find()) {
                lastEnd = m.end();
                long t = timestampMs(m.group(1), m.group(2), m.group(3));
                if (t >= 0) {
                    times.add(t);
                }
            }
            if (times.isEmpty()) {
                continue;
            }
            String text = (lastEnd >= 0 && lastEnd <= raw.length())
                    ? raw.substring(lastEnd).strip() : "";
            for (long t : times) {
                lines.add(new LyricLine(t, text));
            }
        }
        lines.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
        return lines;
    }

    /** Splits plain lyrics into lines, trimming trailing blank lines. */
    public static List<String> parsePlain(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\\R", -1)));
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    private static long timestampMs(String mm, String ss, String frac) {
        try {
            long minutes = Long.parseLong(mm);
            long seconds = Long.parseLong(ss);
            long fraction = 0L;
            if (frac != null && !frac.isEmpty()) {
                if (frac.length() == 1) {
                    fraction = Long.parseLong(frac) * 100L;
                } else if (frac.length() == 2) {
                    fraction = Long.parseLong(frac) * 10L;
                } else {
                    fraction = Long.parseLong(frac.substring(0, 3));
                }
            }
            return minutes * 60_000L + seconds * 1_000L + fraction;
        } catch (NumberFormatException e) {
            return -1L;
        }
    }
}
