package kz.regullar.fastmedia.internal;
public final class LyricsJsonParser {

    private LyricsJsonParser() {
    }

    public static final class LyricsRaw {
        private final String syncedLyrics;
        private final String plainLyrics;

        public LyricsRaw(String syncedLyrics, String plainLyrics) {
            this.syncedLyrics = syncedLyrics;
            this.plainLyrics = plainLyrics;
        }

        public String syncedLyrics() {
            return syncedLyrics;
        }

        public String plainLyrics() {
            return plainLyrics;
        }

        public boolean isPresent() {
            return (syncedLyrics != null && !syncedLyrics.isBlank())
                    || (plainLyrics != null && !plainLyrics.isBlank());
        }
    }
    public static LyricsRaw parseObject(String json) {
        if (json == null || json.isEmpty()) {
            return new LyricsRaw(null, null);
        }
        String synced = extractString(json, "syncedLyrics");
        String plain = extractString(json, "plainLyrics");
        return new LyricsRaw(synced, plain);
    }
    public static LyricsRaw parseBestFromArray(String jsonArray) {
        if (jsonArray == null || jsonArray.isEmpty()) {
            return new LyricsRaw(null, null);
        }
        int pos = 0;
        int len = jsonArray.length();
        LyricsRaw bestPlain = null;
        while (pos < len) {
            int start = jsonArray.indexOf('{', pos);
            if (start == -1) {
                break;
            }
            int depth = 0;
            boolean inStr = false;
            int end = -1;
            for (int i = start; i < len; i++) {
                char c = jsonArray.charAt(i);
                if (inStr) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inStr = false;
                    }
                } else if (c == '"') {
                    inStr = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    if (--depth == 0) {
                        end = i;
                        break;
                    }
                }
            }
            if (end == -1) {
                break;
            }
            String synced = extractString(jsonArray, "syncedLyrics", start, end + 1);
            String plain = extractString(jsonArray, "plainLyrics", start, end + 1);
            LyricsRaw raw = new LyricsRaw(synced, plain);
            if (raw.syncedLyrics() != null && !raw.syncedLyrics().isBlank()) {
                return raw;
            }
            if (bestPlain == null && raw.plainLyrics() != null && !raw.plainLyrics().isBlank()) {
                bestPlain = raw;
            }
            pos = end + 1;
        }
        return bestPlain != null ? bestPlain : new LyricsRaw(null, null);
    }

    public static String extractString(String obj, String key) {
        if (obj == null) return null;
        return extractString(obj, key, 0, obj.length());
    }

    public static String extractString(String obj, String key, int start, int end) {
        if (obj == null) return null;
        String needle = "\"" + key + "\"";
        int pos = start;
        int len = Math.min(end, obj.length());
        while ((pos = obj.indexOf(needle, pos)) != -1 && pos < len) {
            int after = pos + needle.length();
            while (after < len && Character.isWhitespace(obj.charAt(after))) {
                after++;
            }
            if (after >= len || obj.charAt(after) != ':') {
                pos = after;
                continue;
            }
            after++;
            while (after < len && Character.isWhitespace(obj.charAt(after))) {
                after++;
            }
            if (after >= len) {
                return null;
            }
            if (obj.charAt(after) == '"') {
                return readJsonString(obj, after, len);
            }
            return null;
        }
        return null;
    }

    private static String readJsonString(String json, int startQuoteIdx, int maxEnd) {
        StringBuilder sb = new StringBuilder();
        int pos = startQuoteIdx + 1;
        int len = Math.min(maxEnd, json.length());
        while (pos < len) {
            char c = json.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c == '\\' && pos < len) {
                char e = json.charAt(pos++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case '/': sb.append('/'); break;
                    case '\\': sb.append('\\'); break;
                    case '"': sb.append('"'); break;
                    case 'u': {
                        if (pos + 4 <= len) {
                            try {
                                int cp = Integer.parseInt(json, pos, pos + 4, 16);
                                pos += 4;
                                if (Character.isHighSurrogate((char) cp) && pos + 6 <= len
                                        && json.charAt(pos) == '\\' && json.charAt(pos + 1) == 'u') {
                                    int lo = Integer.parseInt(json, pos + 2, pos + 6, 16);
                                    pos += 6;
                                    sb.appendCodePoint(Character.toCodePoint((char) cp, (char) lo));
                                } else {
                                    sb.append((char) cp);
                                }
                            } catch (NumberFormatException ignored) {
                            }
                        }
                        break;
                    }
                    default: sb.append(e); break;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
