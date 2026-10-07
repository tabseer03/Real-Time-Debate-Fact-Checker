package com.debatechecker.factcheck;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which of the model's answers are kept. No network: the answers are written out here. */
class GeminiJudgeTest {

    private static final Evidence AP = new Evidence("Web", "apnews.com, 2015-02-12: NAFTA shadows Obama's efforts",
            "https://apnews.com/x", "Near the heart of every trade argument lurks NAFTA, a breakthrough deal passed "
            + "mainly by Republicans in Congress and signed by a Democratic president, Bill Clinton.", "");
    private static final Evidence DNA = new Evidence("Web", "dnainfo.com, 2016-08-04: Murders, Rapes Up in July",
            "https://dnainfo.com/x", "Murders and rapes were up slightly in July while overall major crime continued "
            + "its downward trend this year, officials said Thursday.", "");
    private static final List<GeminiJudge.Case> CASES = List.of(
            new GeminiJudge.Case("", "NAFTA was signed by Bill Clinton.", List.of(DNA, AP)),
            new GeminiJudge.Case("Donald Trump", "Hillary Clinton has been fighting ISIS her entire adult life.", List.of(DNA)),
            new GeminiJudge.Case("", "Murders in New York City are up this year.", List.of(DNA)));

    private static List<GeminiJudge.Ruling> rulings(String claims) throws IOException {
        return GeminiJudge.rulings(new ObjectMapper().readTree("{\"claims\":[" + claims + "]}"), CASES);
    }

    private static String answer(int n, String verdict, int passage, String quote) {
        return "{\"n\":" + n + ",\"verdict\":\"" + verdict + "\",\"passage\":" + passage + ",\"quote\":\"" + quote
                + "\",\"reason\":\"Because.\"}";
    }

    @Test
    void aVerdictIsKeptWithThePassageItsQuoteIsIn() throws IOException {
        // The model named passage 1; the words are in passage 2, and differ in capitals and punctuation.
        List<GeminiJudge.Ruling> got = rulings(answer(1, "TRUE", 1, "Signed by a Democratic president Bill Clinton"));
        assertEquals(FactChecker.Verdict.TRUE, got.get(0).verdict());
        assertEquals(AP, got.get(0).source());
        assertEquals("Because.", got.get(0).reason());
        assertNull(got.get(1));
        assertNull(got.get(2));
    }

    @Test
    void aVerdictWithoutWordsFromAPassageIsDropped() throws IOException {
        List<GeminiJudge.Ruling> got = rulings(
                answer(2, "FALSE", 0, "") + ","                                          // from memory
                + answer(3, "TRUE", 1, "Murders were up this year in New York") + ","   // reworded
                + answer(1, "TRUE", 2, "Bill Clinton"));                                 // too short to mean anything
        assertEquals(List.of(), got.stream().filter(r -> r != null).toList());
    }

    @Test
    void aVerdictThatDisagreesWithItselfIsDropped() throws IOException {
        // Seen live: TRUE for "Obama was born in Kenya", quoting a fact-check that calls it false.
        String quote = "Murders and rapes were up slightly in July";
        String flipped = "{\"n\":3,\"quote\":\"" + quote + "\",\"passage\":1,\"reason\":\"x\",\"agrees\":false,\"verdict\":\"TRUE\"}";
        assertNull(rulings(flipped).get(2));
        assertEquals(FactChecker.Verdict.FALSE, rulings(flipped.replace("TRUE", "FALSE")).get(2).verdict());
        assertEquals(FactChecker.Verdict.MISLEADING, rulings(flipped.replace("TRUE", "MISLEADING")).get(2).verdict());
    }

    @Test
    void nobodyIsAWitnessForThemselves() throws IOException {
        // All three seen live on the first 2012 Obama-Romney debate.
        Evidence remarks = new Evidence("Web", "obamawhitehouse.archives.gov, 2010-08-18: Remarks by the President",
                "https://obamawhitehouse.archives.gov/the-press-office/x", "And when I was sworn in about 18 months ago, "
                + "we had already lost several million jobs and we were about to lose several million more.", "");
        Evidence rally = new Evidence("Web", "washingtonpost.com, 2012-09-26: Romney calls election 'dramatic choice'",
                "https://www.washingtonpost.com/x", "At Wednesday morning's rally, Romney said his heart aches, noting "
                + "that a woman in her 50s told him she had been out of work since May.", "");
        Evidence bernanke = new Evidence("Web", "nbcnews.com, 2011-01-27: Panel blames deregulation",
                "https://www.nbcnews.com/x", "Federal Reserve Chairman Ben Bernanke told the panel that the crisis put "
                + "12 of the 13 most important U.S. financial firms at risk of failure.", "");
        List<GeminiJudge.Case> cases = List.of(
                new GeminiJudge.Case("Obama", "Millions of jobs were lost.", List.of(remarks)),
                new GeminiJudge.Case("Governor Romney", "A woman in Dayton said she had been out of work since May.", List.of(rally)),
                new GeminiJudge.Case("Obama", "It was the worst financial crisis since the Great Depression.", List.of(bernanke)));
        List<GeminiJudge.Ruling> got = GeminiJudge.rulings(new ObjectMapper().readTree("{\"claims\":["
                + answer(1, "TRUE", 1, "we had already lost several million jobs") + ","
                + answer(2, "TRUE", 1, "Romney said his heart aches, noting that a woman in her 50s told him she had been out of work since May") + ","
                + answer(3, "TRUE", 1, "the crisis put 12 of the 13 most important U.S. financial firms at risk of failure") + "]}"), cases);
        assertNull(got.get(0));     // somebody speaking: "we"
        assertNull(got.get(1));     // the speaker telling the story himself
        assertEquals(bernanke, got.get(2).source());    // somebody else, and "U.S." is not "us"

        assertEquals(true, GeminiJudge.ownSide(remarks, "Obama"));
        assertEquals(true, GeminiJudge.ownSide(new Evidence("Web", "", "https://www.mittromney.com/jobs", "x", ""), "Governor Romney"));
        assertEquals(true, GeminiJudge.ownSide(remarks, "Governor Romney"));     // a government's own site, whoever speaks
        assertEquals(false, GeminiJudge.ownSide(bernanke, "Obama"));
        assertEquals(false, GeminiJudge.ownSide(new Evidence("Web", "", "https://www.bls.gov/news", "x", ""), "Obama"));
    }

    @Test
    void withoutAVerdictTheClosestThingFoundIsKept() throws IOException {
        // "Murders ... are up this year" is not settled by one month, but the viewer can be shown it.
        String close = "{\"n\":3,\"closest\":\"Murders and rapes were up slightly in July\",\"quote\":\"\",\"passage\":0,"
                + "\"reason\":\"One month, not the year.\",\"agrees\":false,\"verdict\":\"NOT ENOUGH\"}";
        List<GeminiJudge.Ruling> got = rulings(close);
        assertEquals(FactChecker.Verdict.UNVERIFIABLE, got.get(2).verdict());
        assertEquals(DNA, got.get(2).source());
        assertEquals("One month, not the year.", got.get(2).reason());
        // Still only words that are in a passage.
        assertNull(rulings(close.replace("up slightly in July", "up four percent in 2016")).get(2));
        // A verdict that is thrown away (its quote is nowhere) falls back on the closest sentence, without its reason.
        got = rulings(close.replace("NOT ENOUGH", "FALSE").replace("\"quote\":\"\"", "\"quote\":\"Murders fell sharply all year long\""));
        assertEquals(FactChecker.Verdict.UNVERIFIABLE, got.get(2).verdict());
        assertEquals("", got.get(2).reason());
    }

    @Test
    void notEnoughAndNumbersOutOfRangeGiveNothing() throws IOException {
        List<GeminiJudge.Ruling> got = rulings(
                answer(1, "NOT ENOUGH", 0, "") + "," + answer(7, "TRUE", 1, "Murders and rapes were up slightly"));
        assertEquals(3, got.size());
        assertEquals(List.of(), got.stream().filter(r -> r != null).toList());
    }
}
