package com.debatechecker.factcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of fact-checking that need no network: which published review counts, and what its rating means. */
class FactCheckMatchingTest {

    /** The shape of a claims:search answer, per the Fact Check Tools API reference. */
    private static final String RESPONSE = """
            {"claims": [
              {"text": "Donald Trump released his tax returns.", "claimant": "Viral post",
               "claimReview": [{"publisher": {"name": "Snopes", "site": "snopes.com"}, "url": "https://example.org/a",
                                "title": "Did Trump release his returns?", "textualRating": "False"}]},
              {"text": "Donald Trump has not released his tax returns", "claimant": "Hillary Clinton",
               "claimReview": [{"publisher": {"name": "PolitiFact", "site": "politifact.com"}, "url": "https://example.org/b",
                                "title": "Clinton on Trump's taxes", "textualRating": "True"}]},
              {"text": "The unemployment rate is 8 percent", "claimant": "A senator",
               "claimReview": [{"publisher": {"name": "FactCheck.org"}, "url": "https://example.org/c",
                                "title": "Jobs numbers", "textualRating": "Mostly False"}]},
              {"text": "President Donald Trump is required by law not to show his tax returns.", "claimant": "Social media user",
               "claimReview": [{"publisher": {"name": "USA Today"}, "url": "https://example.org/e",
                                "title": "Trump can legally release his returns", "textualRating": "False"}]},
              {"text": "A 1991 literary client list promotional booklet identified Barack Obama as having been born in Kenya.",
               "claimReview": [{"publisher": {"name": "Snopes.com"}, "url": "https://example.org/f",
                                "title": "Booklet", "textualRating": "True"}]},
              {"text": "Former President Barack Obama is relocating to Kenya.", "claimant": "social media users",
               "claimReview": [{"publisher": {"name": "AP News"}, "url": "https://example.org/g",
                                "title": "Satire", "textualRating": "False"}]},
              {"text": "Vaccines cause autism", "claimant": "A blog",
               "claimReview": [{"publisher": {"name": "AFP"}, "url": "https://example.org/d",
                                "title": "No", "textualRating": "False"}]}
            ]}""";

    private static JsonNode response() throws Exception {
        return new ObjectMapper().readTree(RESPONSE);
    }

    @Test
    void aReviewOfTheOppositeClaimIsNotUsed() throws Exception {
        List<Evidence> found = GoogleFactCheck.matches("Donald Trump has not released his tax returns.", response());
        assertEquals(1, found.size());
        assertEquals("True", found.get(0).rating());
        assertEquals("PolitiFact: Clinton on Trump's taxes", found.get(0).title());

        found = GoogleFactCheck.matches("Donald Trump released his tax returns.", response());
        assertEquals(1, found.size());
        assertEquals("False", found.get(0).rating());
    }

    @Test
    void aReviewOfADifferentFigureIsNotUsed() throws Exception {
        assertTrue(GoogleFactCheck.matches("The unemployment rate is 5 percent.", response()).isEmpty());
        assertEquals(1, GoogleFactCheck.matches("The unemployment rate is 8 percent.", response()).size());
    }

    /** Both seen with the real API on 2026-10-05; the first gave FALSE, the second would have given TRUE. */
    @Test
    void aReviewOfANarrowerClaimOnTheSameTopicIsNotUsed() throws Exception {
        for (Evidence e : GoogleFactCheck.matches("Donald Trump has not released his tax returns.", response())) {
            assertEquals("PolitiFact: Clinton on Trump's taxes", e.title());
        }
        assertTrue(GoogleFactCheck.matches("Barack Obama was born in Kenya.", response()).isEmpty());
    }

    @Test
    void anUnrelatedReviewIsNotUsed() throws Exception {
        assertTrue(GoogleFactCheck.matches("Ford is moving thousands of jobs to Mexico.", response()).isEmpty());
        assertTrue(GoogleFactCheck.matches("Anything", new ObjectMapper().readTree("{}")).isEmpty());
    }

    @Test
    void ratingsBecomeVerdicts() {
        assertEquals(FactChecker.Verdict.FALSE, FactChecker.verdictOf("Pants on Fire"));
        assertEquals(FactChecker.Verdict.FALSE, FactChecker.verdictOf("Mostly False"));
        assertEquals(FactChecker.Verdict.FALSE, FactChecker.verdictOf("Not true"));
        assertEquals(FactChecker.Verdict.FALSE, FactChecker.verdictOf("Four Pinocchios"));
        assertEquals(FactChecker.Verdict.FALSE, FactChecker.verdictOf("Inaccurate"));
        assertEquals(FactChecker.Verdict.TRUE, FactChecker.verdictOf("Mostly True"));
        assertEquals(FactChecker.Verdict.TRUE, FactChecker.verdictOf("Accurate"));
        assertEquals(FactChecker.Verdict.MISLEADING, FactChecker.verdictOf("Half True"));
        assertEquals(FactChecker.Verdict.MISLEADING, FactChecker.verdictOf("Missing context"));
        assertEquals(FactChecker.Verdict.MISLEADING, FactChecker.verdictOf("Two Pinocchios"));
        assertEquals(FactChecker.Verdict.MISLEADING, FactChecker.verdictOf("Exaggerated"));
        assertEquals(FactChecker.Verdict.UNVERIFIABLE, FactChecker.verdictOf("Satire"));
        assertEquals(FactChecker.Verdict.UNVERIFIABLE, FactChecker.verdictOf("It's complicated"));
    }

    @Test
    void sameClaimNeedsSameFiguresAndSameDenial() {
        assertTrue(Words.couldBeSame("We have 20 trillion in debt.", "The debt is 20 trillion dollars"));
        assertFalse(Words.couldBeSame("We have 20 trillion in debt.", "We have 10 trillion in debt."));
        assertFalse(Words.couldBeSame("He paid taxes.", "He didn't pay taxes."));
    }

    @Test
    void namedSpeakerGoesInFrontOfAClaimLeftAsSaid() {
        assertEquals("Donald Trump: I have a great company.",
                FactChecker.query("I have a great company.", false, "Donald Trump"));
        assertEquals("I have a great company.", FactChecker.query("I have a great company.", false, "S2"));
        assertEquals("Donald Trump has a great company.",
                FactChecker.query("Donald Trump has a great company.", true, "Donald Trump"));
    }
}
