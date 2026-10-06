package com.debatechecker.factcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds news passages that bear on a claim, through Exa's search API (exa.ai): one request per
 * claim, which returns the pages and, from each, the passage Exa finds closest to the claim.
 *
 * Every request is paid for out of a monthly free allowance ($10; a request for five results with
 * passages cost $0.007 on 2026-10-06, so about 1,400 claims a month), so what was spent is counted.
 */
public final class ExaEvidence implements EvidenceSource {

    private static final String API = "https://api.exa.ai/search";
    private static final int RESULTS = 5;
    private static final int MAX_PASSAGE_CHARS = 500;
    /** As for Wikipedia: "There is no leadership." has one word to look for. Not worth paying for. */
    private static final int MIN_CLAIM_WORDS = 4;
    /** With a date limit, how far back to look. */
    private static final int YEARS_BACK = 3;
    private static final Pattern SITE = Pattern.compile("^https?://(?:www\\.)?([^/:?#]+)");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String key;
    private final Duration timeout;
    private final LocalDate before;
    private final DoubleAdder spentDollars = new DoubleAdder();

    /**
     * @param before only pages published before this day, or null for no limit. Set it to the day
     *               of the debate when replaying an old one: without it the search finds articles
     *               fact-checking that very debate, which a live debate never has.
     */
    public ExaEvidence(String key, Duration timeout, LocalDate before) {
        this.key = key == null ? "" : key.trim();
        this.timeout = timeout;
        this.before = before;
    }

    public boolean enabled() {
        return !key.isEmpty();
    }

    /** What the requests so far have cost, as Exa reported it. */
    public double spentDollars() {
        return spentDollars.sum();
    }

    @Override
    public List<Evidence> search(String claim, int max) throws IOException, InterruptedException {
        if (!enabled() || Words.contentSet(claim).size() < MIN_CLAIM_WORDS) return List.of();
        ObjectNode body = json.createObjectNode();
        body.put("query", claim);
        body.put("numResults", RESULTS);
        body.put("category", "news");
        body.putObject("contents").put("highlights", true);
        if (before != null) {
            // Both ends: with only an end date Exa also returned pages it has no date for, among
            // them 2022 articles for a limit in 2016.
            body.put("startPublishedDate", before.minusYears(YEARS_BACK) + "T00:00:00.000Z");
            body.put("endPublishedDate", before + "T00:00:00.000Z");
        }
        // Timed as a whole, like the Wikipedia request: the request's own timeout stops at the headers.
        CompletableFuture<HttpResponse<String>> pending = http.sendAsync(
                HttpRequest.newBuilder(URI.create(API)).timeout(timeout)
                        .header("Content-Type", "application/json")
                        .header("x-api-key", key)
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw new IOException("Exa took over " + timeout.toSeconds() + " s");
        } catch (ExecutionException e) {
            throw new IOException("Exa: " + e.getCause(), e.getCause());
        }
        if (response.statusCode() != 200) {
            String said = response.body();
            throw new IOException("Exa returned " + response.statusCode() + ": "
                    + said.substring(0, Math.min(200, said.length())));
        }
        JsonNode root = json.readTree(response.body());
        spentDollars.add(root.path("costDollars").path("total").asDouble(0));
        return passages(root, max, before);
    }

    /** One passage per page, in Exa's order. With a date limit, a page with no date is left out. */
    static List<Evidence> passages(JsonNode root, int max, LocalDate before) {
        List<Evidence> out = new ArrayList<>();
        for (JsonNode r : root.path("results")) {
            String published = r.path("publishedDate").asText("");
            String day = published.length() >= 10 ? published.substring(0, 10) : "";
            if (before != null && (day.isEmpty() || day.compareTo(before.toString()) >= 0)) continue;
            String passage = r.path("highlights").path(0).asText("").replaceAll("\\s+", " ").trim();
            if (passage.isEmpty()) continue;
            String url = r.path("url").asText("");
            Matcher site = SITE.matcher(url);
            String title = r.path("title").asText("").trim();
            out.add(new Evidence("Web",
                    (site.find() ? site.group(1) : "") + (day.isEmpty() ? "" : ", " + day)
                            + (title.isEmpty() ? "" : ": " + title),
                    url, passage.length() > MAX_PASSAGE_CHARS ? passage.substring(0, MAX_PASSAGE_CHARS) : passage, ""));
            if (out.size() == max) break;
        }
        return out;
    }
}
