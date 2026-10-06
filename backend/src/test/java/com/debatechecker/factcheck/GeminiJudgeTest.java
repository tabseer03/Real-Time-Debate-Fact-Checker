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
    void notEnoughAndNumbersOutOfRangeGiveNothing() throws IOException {
        List<GeminiJudge.Ruling> got = rulings(
                answer(1, "NOT ENOUGH", 0, "") + "," + answer(7, "TRUE", 1, "Murders and rapes were up slightly"));
        assertEquals(3, got.size());
        assertEquals(List.of(), got.stream().filter(r -> r != null).toList());
    }
}
