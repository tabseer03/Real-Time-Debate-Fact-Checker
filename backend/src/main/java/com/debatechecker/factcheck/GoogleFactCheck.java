package com.debatechecker.factcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Looks a claim up in Google's Fact Check Tools API: fact-checks already published by PolitiFact,
 * FactCheck.org, the Washington Post and others (ClaimReview markup). A hit comes with the
 * fact-checker's own rating, so no LLM is needed for the verdict. Free, but needs an API key.
 */
public final class GoogleFactCheck {

    private static final String URL = "https://factchecktools.googleapis.com/v1alpha1/claims:search";
    /**
     * Google returns whatever is nearest, and most of it is fact-checks of other claims on the same
     * topic ("A 1991 booklet identified Barack Obama as having been born in Kenya": True). A review
     * counts only if each claim has this much of the other's content words.
     */
    private static final double MIN_OVERLAP = 0.6;
    private static final int MIN_SHARED_WORDS = 3;
    private static final Duration MAX_WAIT = Duration.ofSeconds(4);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String key;
    private final Duration timeout;

    /** @param key "" switches the lookup off */
    public GoogleFactCheck(String key, Duration timeout) {
        this.key = key == null ? "" : key.trim();
        // It answers in about a second or, now and then, not at all; don't hold a verdict for that.
        this.timeout = timeout.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : timeout;
    }

    public boolean enabled() {
        return !key.isEmpty();
    }

    /** @return published fact-checks of this claim, best match first; empty if there are none */
    public List<Evidence> search(String claim) throws IOException, InterruptedException {
        if (!enabled()) return List.of();
        String url = URL + "?languageCode=en&pageSize=10&query=" + URLEncoder.encode(claim, StandardCharsets.UTF_8)
                + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        HttpResponse<String> response = http.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(timeout).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Google Fact Check returned " + response.statusCode() + ": "
                    + json.readTree(response.body()).path("error").path("message").asText(response.body()));
        }
        return matches(claim, json.readTree(response.body()));
    }

    /** Package-private so the matching can be tried on a saved response. */
    static List<Evidence> matches(String claim, JsonNode response) {
        Set<String> ours = Words.contentSet(claim);
        record Scored(double score, Evidence evidence) {}
        List<Scored> scored = new ArrayList<>();
        for (JsonNode c : response.path("claims")) {
            String reviewed = c.path("text").asText("");
            Set<String> theirs = Words.contentSet(reviewed);
            // Both ways: the review's own extra words make it another claim. "Trump is required by
            // law not to show his tax returns" (False) has 4 of the 5 words of "Trump has not
            // released his tax returns", which has only half of its words.
            double overlap = Math.min(Words.covered(ours, theirs), Words.covered(theirs, ours));
            if (overlap < MIN_OVERLAP || Words.covered(ours, theirs) * ours.size() < MIN_SHARED_WORDS) continue;
            // "False" on "Trump released his tax returns" is not "False" on "Trump has not released ...".
            if (!Words.couldBeSame(claim, reviewed)) continue;
            for (JsonNode r : c.path("claimReview")) {
                String rating = r.path("textualRating").asText("").trim();
                if (rating.isEmpty()) continue;
                String publisher = r.path("publisher").path("name").asText(r.path("publisher").path("site").asText(""));
                String headline = r.path("title").asText("");
                String claimant = c.path("claimant").asText("");
                scored.add(new Scored(overlap, new Evidence("Google Fact Check",
                        headline.isEmpty() ? publisher : publisher + ": " + headline,
                        r.path("url").asText(""),
                        claimant.isEmpty() ? reviewed : claimant + ": " + reviewed,
                        rating)));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Evidence> out = new ArrayList<>();
        for (Scored s : scored) out.add(s.evidence());
        return out;
    }
}
