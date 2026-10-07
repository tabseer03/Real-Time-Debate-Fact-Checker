package com.debatechecker.claims;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Rewrites seen live that must fall back on the speaker's own words. */
class ClaimCheckerTest {

    private static final List<String> NAMES = List.of("Governor Romney", "Obama", "the moderator");

    private static String problem(String said, String rewrite) {
        return ClaimChecker.problem(said, rewrite, List.of(), NAMES);
    }

    @Test
    void aRewriteMayNotOpenWithAPronounTheSpeakerDidNotUse() {
        // session-dc70f661: both were then left out of the minute's check as unresolved.
        assertNotNull(problem("First of all, I don't have a $5 trillion tax cut.", "He does not have a $5 trillion tax cut."));
        assertNotNull(problem("And by the way, I like coal.", "He likes coal."));
    }

    @Test
    void aPronounTheSpeakerUsedIsLeftAlone() {
        assertEquals(null, problem("And he hasn't been able to identify them.", "He hasn't been able to identify them."));
        assertEquals(null, problem("I don't have a $5 trillion tax cut.", "Governor Romney does not have a $5 trillion tax cut."));
    }
}
