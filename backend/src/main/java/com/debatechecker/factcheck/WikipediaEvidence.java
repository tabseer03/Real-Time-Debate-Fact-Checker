package com.debatechecker.factcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Finds passages on English Wikipedia that might settle a claim.
 *
 * One request per claim: the search and the plain text of the pages it finds come back together
 * (generator=search with prop=cirrusdoc). Searching and then fetching each page was four requests,
 * and Wikipedia answered 429 "wait 37 s" after about fifteen of those.
 */
public final class WikipediaEvidence {

    private static final String API = "https://en.wikipedia.org/w/api.php?action=query&generator=search"
            + "&gsrlimit=%d&prop=cirrusdoc&cdincludes=text&format=json&formatversion=2&gsrsearch=%s";
    private static final int PAGES = 3;
    private static final int MAX_PASSAGE_CHARS = 500;
    private static final double MIN_COVERED = 0.5;
    /**
     * "There is no leadership." has one word to look for and "That starts with Secretary Clinton."
     * three; they found bibliographies and Clinton's travels. Not worth a request.
     */
    private static final int MIN_CLAIM_WORDS = 4;
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z\"])");
    /**
     * The page text includes the reference list: "Retrieved May 19, 2016.", "Archived from the
     * original on ...", an article's headline in quotes, "Tuttle, Brad (February 7, 2019).". Headlines
     * repeat the claim's words better than any sentence of the article does, and say nothing.
     */
    private static final Pattern CITATION = Pattern.compile(
            "^\"|\\bRetrieved (?:on )?(?:\\d|January|February|March|April|May|June|July|August|September|October|"
                    + "November|December)|Archived from the original|Wayback Machine|\\bISBN\\b|\\bS2CID\\b|\\bdoi:|\\bpp\\."
                    + "|\\((?:\\d{1,2} )?[A-Z][a-z]+ (?:\\d{1,2}, )?\\d{4}\\)\\.");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String userAgent;
    private final Duration timeout;
    /** After a 429: no requests until this time ({@link System#nanoTime}). */
    private volatile long blockedUntilNanos = System.nanoTime();

    /** @param userAgent Wikimedia asks for one that names the program and a way to reach its operator */
    public WikipediaEvidence(String userAgent, Duration timeout) {
        this.userAgent = userAgent;
        this.timeout = timeout;
    }

    /**
     * @param max how many passages to return at most
     * @return the passages most like the claim, best first; empty if nothing was found
     * @throws IOException if Wikipedia cannot be reached or has asked us to wait
     */
    public List<Evidence> search(String claim, int max) throws IOException, InterruptedException {
        if (Words.contentSet(claim).size() < MIN_CLAIM_WORDS) return List.of();
        long waitMs = (blockedUntilNanos - System.nanoTime()) / 1_000_000;
        if (waitMs > 0) throw new IOException("Wikipedia asked us to wait another " + (waitMs / 1000 + 1) + " s");

        String url = String.format(API, PAGES, URLEncoder.encode(claim, StandardCharsets.UTF_8));
        // The request's own timeout stops at the headers, and the body is hundreds of KB: one
        // lookup took 40 s. The whole answer has to arrive in time.
        CompletableFuture<HttpResponse<byte[]>> pending = http.sendAsync(
                HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
                        .header("User-Agent", userAgent)
                        .header("Accept-Encoding", "gzip")
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
        HttpResponse<byte[]> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException("Wikipedia took over " + timeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            throw new IOException("Wikipedia: " + e.getCause(), e.getCause());
        }
        try (InputStream raw = new ByteArrayInputStream(response.body())) {
            if (response.statusCode() == 429) {
                long seconds = response.headers().firstValue("retry-after").map(Long::parseLong).orElse(30L);
                blockedUntilNanos = System.nanoTime() + seconds * 1_000_000_000L;
                throw new IOException("Wikipedia returned 429, retry after " + seconds + " s");
            }
            if (response.statusCode() != 200) throw new IOException("Wikipedia returned " + response.statusCode());
            boolean gzip = response.headers().firstValue("Content-Encoding").orElse("").contains("gzip");
            JsonNode root = json.readTree(gzip ? new GZIPInputStream(raw) : raw);

            List<String[]> pages = new ArrayList<>();   // {title, text}, in search order
            List<JsonNode> nodes = new ArrayList<>();
            root.path("query").path("pages").forEach(nodes::add);
            nodes.sort(Comparator.comparingInt(n -> n.path("index").asInt()));
            for (JsonNode p : nodes) {
                String text = p.path("cirrusdoc").path(0).path("source").path("text").asText("");
                if (!text.isEmpty()) pages.add(new String[] {p.path("title").asText(), text});
            }
            return passages(claim, pages, max);
        }
    }

    /**
     * Every pair of neighbouring sentences is a candidate; it scores the claim's words it contains,
     * rare ones counting more, with a small penalty for length.
     */
    static List<Evidence> passages(String claim, List<String[]> pages, int max) {
        record Candidate(String title, String text, Set<String> words) {}
        Set<String> wanted = Words.contentSet(claim);
        List<Candidate> candidates = new ArrayList<>();
        for (String[] page : pages) {
            List<String> sentences = new ArrayList<>();
            for (String s : SENTENCE_END.split(page[1])) {
                s = s.trim();
                if (s.length() > 40 && !CITATION.matcher(s).find()) sentences.add(s);
            }
            for (int i = 0; i < sentences.size(); i++) {
                String text = i + 1 < sentences.size() ? sentences.get(i) + " " + sentences.get(i + 1) : sentences.get(i);
                candidates.add(new Candidate(page[0], text, Words.contentSet(text)));
            }
        }
        Map<String, Integer> seenIn = new HashMap<>();
        for (Candidate c : candidates) {
            for (String w : wanted) if (c.words().contains(w)) seenIn.merge(w, 1, Integer::sum);
        }
        record Scored(double score, Candidate candidate) {}
        List<Scored> scored = new ArrayList<>();
        for (Candidate c : candidates) {
            // "People are leaving our country ... because of bureaucratic red tape." found the page
            // "Red": a passage with under half of the claim's words is about something else.
            if (Words.covered(wanted, c.words()) < MIN_COVERED) continue;
            double score = 0;
            for (String w : wanted) {
                if (c.words().contains(w)) score += Math.log(1 + (double) candidates.size() / (1 + seenIn.get(w)));
            }
            if (score > 0) scored.add(new Scored(score / (1 + 0.002 * c.text().length()), c));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Evidence> out = new ArrayList<>();
        Set<String> starts = new HashSet<>();
        for (Scored s : scored) {
            String text = s.candidate().text();
            // Neighbouring pairs share a sentence; keep the better of two that overlap.
            boolean overlaps = false;
            for (Evidence e : out) {
                String head = text.substring(0, Math.min(60, text.length()));
                String otherHead = e.text().substring(0, Math.min(60, e.text().length()));
                if (e.text().contains(head) || text.contains(otherHead)) overlaps = true;
            }
            if (overlaps || !starts.add(text.substring(0, Math.min(60, text.length())))) continue;
            String title = s.candidate().title();
            out.add(new Evidence("Wikipedia", title,
                    "https://en.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8),
                    text.length() > MAX_PASSAGE_CHARS ? text.substring(0, MAX_PASSAGE_CHARS) : text, ""));
            if (out.size() == max) break;
        }
        return out;
    }
}
