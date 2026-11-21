package kz.regullar.fastmedia.internal;

import kz.regullar.fastmedia.LyricLine;
import kz.regullar.fastmedia.Lyrics;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class LyricsClient {
    private static final Logger LOG = Logger.getLogger(LyricsClient.class.getName());
    private static final String BASE_URL = "https://lrclib.net";
    private static final String USER_AGENT = "FastMedia/2.0";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(4);

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String[] ARTIST_SEPARATORS = {
            " feat. ", " feat ", " ft. ", " ft ", ", ", " & ", " x ", "; ", " / "
    };

    private static final String[] FEAT_MARKERS = {
            " feat", " ft.", " ft ", "(feat", "[feat"
    };

    private LyricsClient() {
    }

    private record Candidate(String artist, String track) {
    }

    public static Lyrics fetchLyrics(String artist, String title) {
        if (title == null || title.isBlank()) {
            return Lyrics.notFound();
        }

        String rawArtist = artist != null ? artist.strip() : "";
        String rawTitle = title.strip();
        String primaryArtist = primaryArtist(rawArtist);
        String strippedTitle = stripTrackDecorations(rawTitle);

        Set<Candidate> candidates = new LinkedHashSet<>();
        candidates.add(new Candidate(primaryArtist, strippedTitle));
        candidates.add(new Candidate(rawArtist, rawTitle));
        if (!primaryArtist.equals(rawArtist)) {
            candidates.add(new Candidate(primaryArtist, rawTitle));
        }
        if (!strippedTitle.equals(rawTitle)) {
            candidates.add(new Candidate(rawArtist, strippedTitle));
        }

        List<Candidate> candidateList = new java.util.ArrayList<>(candidates);
        if (candidateList.isEmpty()) {
            return Lyrics.notFound();
        }

        Candidate first = candidateList.get(0);
        String bestSynced = null;
        String bestPlain = null;
        boolean transientError = false;

        try {
            String path = "/api/get?artist_name=" + urlEncode(first.artist())
                    + "&track_name=" + urlEncode(first.track());
            HttpResponse<String> resp = executeGetAsync(path).get(REQUEST_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            int status = resp.statusCode();
            if (status >= 200 && status < 300) {
                LyricsJsonParser.LyricsRaw raw = LyricsJsonParser.parseObject(resp.body());
                if (raw.syncedLyrics() != null && !raw.syncedLyrics().isBlank()) {
                    bestSynced = raw.syncedLyrics();
                }
                if (raw.plainLyrics() != null && !raw.plainLyrics().isBlank()) {
                    bestPlain = raw.plainLyrics();
                }
            } else if (status != 404) {
                transientError = true;
            }
        } catch (Exception e) {
            transientError = true;
        }

        if (bestSynced == null && candidateList.size() > 1) {
            List<CompletableFuture<LyricsJsonParser.LyricsRaw>> futures = new java.util.ArrayList<>();
            for (int i = 1; i < candidateList.size(); i++) {
                Candidate c = candidateList.get(i);
                if (c.track().isEmpty()) continue;
                String path = "/api/get?artist_name=" + urlEncode(c.artist())
                        + "&track_name=" + urlEncode(c.track());
                futures.add(executeGetAsync(path).handle((resp, ex) -> {
                    if (ex == null && resp != null && resp.statusCode() >= 200 && resp.statusCode() < 300) {
                        return LyricsJsonParser.parseObject(resp.body());
                    }
                    return null;
                }));
            }
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture<?>[0]))
                        .get(REQUEST_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
            }
            for (CompletableFuture<LyricsJsonParser.LyricsRaw> f : futures) {
                try {
                    LyricsJsonParser.LyricsRaw raw = f.getNow(null);
                    if (raw != null) {
                        if (bestSynced == null && raw.syncedLyrics() != null && !raw.syncedLyrics().isBlank()) {
                            bestSynced = raw.syncedLyrics();
                        }
                        if (bestPlain == null && raw.plainLyrics() != null && !raw.plainLyrics().isBlank()) {
                            bestPlain = raw.plainLyrics();
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }

        if (bestSynced == null) {
            String query = primaryArtist.isEmpty() ? strippedTitle : (primaryArtist + " " + strippedTitle);
            if (!query.isBlank()) {
                try {
                    String path = "/api/search?q=" + urlEncode(query);
                    HttpResponse<String> resp = executeGetAsync(path).get(REQUEST_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    int status = resp.statusCode();
                    if (status >= 200 && status < 300) {
                        LyricsJsonParser.LyricsRaw raw = LyricsJsonParser.parseBestFromArray(resp.body());
                        if (raw.syncedLyrics() != null && !raw.syncedLyrics().isBlank()) {
                            bestSynced = raw.syncedLyrics();
                        }
                        if (bestPlain == null && raw.plainLyrics() != null && !raw.plainLyrics().isBlank()) {
                            bestPlain = raw.plainLyrics();
                        }
                    } else if (status != 404) {
                        transientError = true;
                    }
                } catch (Exception e) {
                    LOG.log(Level.FINE, "[FastMedia] lyrics search failed for query " + query, e);
                    transientError = true;
                }
            }
        }

        if (bestSynced != null && !bestSynced.isBlank()) {
            List<LyricLine> synced = LyricsParser.parseSynced(bestSynced);
            if (!synced.isEmpty()) {
                return Lyrics.synced(synced);
            }
        }
        if (bestPlain != null && !bestPlain.isBlank()) {
            List<String> plain = LyricsParser.parsePlain(bestPlain);
            if (!plain.isEmpty()) {
                return Lyrics.plain(plain);
            }
        }

        return transientError ? Lyrics.unavailable() : Lyrics.notFound();
    }

    private static CompletableFuture<HttpResponse<String>> executeGetAsync(String path) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + path))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    public static String primaryArtist(String artist) {
        if (artist == null || artist.isBlank()) {
            return "";
        }
        String lower = artist.toLowerCase(Locale.ROOT);
        int best = -1;
        for (String sep : ARTIST_SEPARATORS) {
            int idx = lower.indexOf(sep);
            if (idx > 0 && (best == -1 || idx < best)) {
                best = idx;
            }
        }
        return best == -1 ? artist.strip() : artist.substring(0, best).strip();
    }

    public static String stripTrackDecorations(String track) {
        if (track == null || track.isBlank()) {
            return "";
        }
        String s = track;
        String lower = s.toLowerCase(Locale.ROOT);
        for (String feat : FEAT_MARKERS) {
            int idx = lower.indexOf(feat);
            if (idx != -1) {
                s = s.substring(0, idx);
                break;
            }
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            s = s.strip();
            if (!s.isEmpty()) {
                char last = s.charAt(s.length() - 1);
                if (last == ')' || last == ']') {
                    char open = last == ')' ? '(' : '[';
                    int idx = s.lastIndexOf(open);
                    if (idx != -1) {
                        s = s.substring(0, idx);
                        changed = true;
                    }
                }
            }
        }
        return s.strip();
    }

    private static String urlEncode(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
