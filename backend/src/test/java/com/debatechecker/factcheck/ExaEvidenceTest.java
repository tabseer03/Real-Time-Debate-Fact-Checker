package com.debatechecker.factcheck;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Where a long passage is cut. No network. */
class ExaEvidenceTest {

    @Test
    void aLongPassageEndsWhereASentenceEnds() {
        String passage = "The panel met for a year. Ben Bernanke told the panel that 12 of the 13 firms were at risk,"
                + " and that it surprised him.";
        assertEquals("The panel met for a year.", ExaEvidence.shortened(passage, 40));
        assertEquals(passage, ExaEvidence.shortened(passage, 500));
        // A sentence that ends exactly at the limit is kept whole.
        assertEquals("The panel met for a year.", ExaEvidence.shortened(passage, 25));
    }

    @Test
    void anAbbreviationIsNotASentenceEnd() {
        // session-49b34e95: the passage was cut at "U.S." and the quote lost what Bernanke said.
        String passage = "It was a bad year. Mr. Bernanke told the panel that the crisis put 12 of the 13 most important"
                + " U.S. financial firms at risk of failure within a week or two, and that it surprised him.";
        assertEquals("It was a bad year.", ExaEvidence.shortened(passage, 30));
        assertEquals("It was a bad year. Mr. Bernanke told the panel that the crisis put 12 of the 13 most important"
                + " U.S. financial firms at risk", ExaEvidence.shortened(passage, 125));
    }

    @Test
    void withNoSentenceEndInReachItEndsAtAWord() {
        String passage = "He promoted a slew of policy provisions aimed at increasing investment in small businesses";
        assertEquals("He promoted a slew of policy provisions aimed at", ExaEvidence.shortened(passage, 52));
        assertEquals("He promoted a slew", ExaEvidence.shortened(passage, 18));
    }
}
