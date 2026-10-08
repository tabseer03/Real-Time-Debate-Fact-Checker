package com.debatechecker.claims;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Rewrites seen live that must fall back on the speaker's own words. */
class ClaimCheckerTest {

    private static final List<String> NAMES = List.of("Governor Romney", "Obama", "the moderator");

    private static String problem(String said, String rewrite) {
        return ClaimChecker.problem(said, rewrite, List.of(), NAMES, "Governor Romney");
    }

    @Test
    void aNumberSpelledOutIsTheSameNumber() {
        // session-0398c5d0: rejected as "adds a number: twenty".
        assertEquals(null, ClaimChecker.problem(
                "but the most important one is that 20 years ago I became the luckiest man on earth because Michelle Obama agreed to marry me.",
                "Twenty years ago, Obama became the luckiest man on earth because Michelle Obama agreed to marry him.",
                List.of(), NAMES, "Obama"));
        assertNotNull(problem("My plan has five basic parts.", "Governor Romney's plan has six basic parts."));
    }

    @Test
    void aNameOnlyStandsInForAWordThatPointsAtSomeone() {
        assertEquals(null, problem("My plan has five basic parts.", "Governor Romney's plan has five basic parts."));
        assertEquals(null, problem("You raised taxes four times.", "Obama raised taxes four times."));
        assertEquals(null, problem("The president raised taxes four times.", "Obama raised taxes four times."));
        assertNotNull(problem("He's lost his most recent job.", "Governor Romney lost his most recent job."));
        assertEquals(null, ClaimChecker.problem("That creates about 4 million jobs.",
                "Governor Romney's plan creates about 4 million jobs.", List.of("My plan has five basic parts."),
                NAMES, "Governor Romney"));
        assertNotNull(problem("The tax plan costs $5 trillion.", "Obama's tax plan costs $5 trillion."));
    }

    @Test
    void courtesiesAndFeelingsAreNotClaims() {
        // session-0398c5d0: all passed the gate at 0.05-0.08 and were shown as claims.
        assertNotNull(NonClaims.inSentence("I'm pleased to be at the University of Denver."));
        assertNotNull(NonClaims.inSentence("I appreciate their welcome and also the presidential commission on these debates."));
        assertNotNull(NonClaims.inSentence("And I'm looking forward to having that debate."));
        assertNotNull(NonClaims.inSentence("I've got a different view."));
        // session-55f94587: became "Donald Trump apologized to his family." and was "contradicted".
        assertNotNull(NonClaims.inSentence("I apologize to my family."));
        assertNotNull(NonClaims.inSentence("that we change our tax code to make sure that we're helping small businesses,"));
        assertNotNull(NonClaims.inSentence("and that we reduce our deficit in a balanced way that allows us to make these critical investments."));
        assertEquals(null, NonClaims.inSentence("That creates about 4 million jobs."));
        assertEquals(null, NonClaims.inSentence("I'm pleased that unemployment fell below 8 percent."));
        assertEquals(null, NonClaims.inSentence("I'm glad that we ended the war in Iraq."));
        assertEquals(null, NonClaims.inSentence("My plan has five basic parts."));
    }

    @Test
    void theSpeakersOwnFeelingStaysOneOnceTheNameIsIn() {
        assertTrue(NonClaims.inRewrite("Obama wishes Michelle Obama a happy anniversary and states that a year from now they will not be celebrating it.", "Obama"));
        assertTrue(NonClaims.inRewrite("Obama believes we should reduce our deficit in a balanced way.", "Obama"));
        assertFalse(NonClaims.inRewrite("Governor Romney believes we should cut taxes by $5 trillion.", "Obama"));
        assertFalse(NonClaims.inRewrite("Governor Romney's plan has five basic parts.", "Governor Romney"));
    }

    @Test
    void aQuotedStoryIsNotTheSpeakersOwnWords() {
        assertTrue(NonClaims.opensQuote("I was in Dayton, Ohio, and a woman grabbed my arm and she said, I've been out of work since May."));
        assertTrue(NonClaims.opensQuote("Ann yesterday was at a rally in Denver and a woman came up to her with a baby in her arms and said, Ann,"));
        assertFalse(NonClaims.opensQuote("The president said we would have unemployment at 5.4 percent."));
        assertTrue(NonClaims.firstPerson("He's lost his most recent job and we've now just lost our home."));
        assertFalse(NonClaims.firstPerson("Oil production in the US is up."));
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
