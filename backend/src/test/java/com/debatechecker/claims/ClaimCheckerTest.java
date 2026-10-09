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
    void aConnectiveOrGoodMannersDoNotSinkARewrite() {
        // session-7a658394: rejected for "whil" and for "drops would".
        assertEquals(null, ClaimChecker.problem(
                "Well the fact is that businesses in America today are paying the second highest tax rate of anywhere in the world. Our tax rates for business in America is 35%. Ireland, it's 11%.",
                "Businesses in America are paying the second highest tax rate of anywhere in the world, at 35%, while Ireland's rate is 11%.",
                List.of(), NAMES, "Senator McCain"));
        assertEquals(null, ClaimChecker.problem(
                "I would like to mention that a couple of days ago, Senator Obama was out in Ohio and he had an encounter with a guy who's a plumber.",
                "A couple of days ago, Senator Obama was out in Ohio and he had an encounter with a guy who's a plumber.",
                List.of(), List.of("Senator Obama", "Senator McCain"), "Senator McCain"));
        assertNotNull(problem("If we cut taxes we would be better off.", "We are better off when we cut taxes."));
        // session-e538ba40: "negation changed on: 98" and "on: 95", "words nobody said: receiv".
        assertEquals(null, ClaimChecker.problem(
                "not only do 98% of small businesses make less than $250,000, but I also want to give them additional tax breaks.",
                "98% of small businesses make less than $250,000, and Obama wants to give them additional tax breaks.",
                List.of(), NAMES, "Obama"));
        assertEquals(null, ClaimChecker.problem(
                "then you will not see your income tax go up, your payroll tax, not one dime. And 95% of working families will get a tax cut.",
                "Your income tax and payroll tax will not go up, and 95% of working families will get a tax cut.",
                List.of(), NAMES, "Obama"));
        assertEquals(null, ClaimChecker.problem(
                "ExxonMobil and other oil companies, for example, would get an additional $4 billion in tax breaks.",
                "ExxonMobil and other oil companies would receive an additional $4 billion in tax breaks.",
                List.of(), NAMES, "Obama"));
        assertNotNull(problem("I did not raise taxes.", "Governor Romney raised taxes."));
        assertEquals("The crisis is the worst since the Great Depression, and the rescue plan is a first step.",
                ClaimChecker.tidy("Obama said that the crisis is the worst since the Great Depression, and that the rescue plan is a first step.", NAMES));
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
        // session of 2026-10-08 evening: "you" turned into the speaker.
        assertNotNull(problem("You're the one that sent the pictures around your campaign.",
                "Governor Romney sent pictures around his campaign."));
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
        // session-7a658394 (McCain-Obama 2008): the start of it was heard on another voice.
        assertNotNull(NonClaims.inSentence("University and the people of New York for hosting us tonight and it's wonderful to join Senator McCain again and thank you, Bub."));
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
    void anotherFormOfAWordThatWasSaidIsNotANewWord() {
        // Rejected in the replays of 2026-10-09 for "loss", "spent", "increas", "occurr", "independenc".
        assertEquals(null, ClaimChecker.problem(
                "Four years ago, we went through the worst financial crisis since the Great Depression. Millions of jobs were lost.",
                "Four years ago, we went through the worst financial crisis since the Great Depression, with millions of job losses.",
                List.of(), NAMES, "Obama"));
        assertEquals(null, ClaimChecker.problem(
                "We saw him after the first debate spend nearly a week denigrating a former Miss Universe.",
                "After the first debate, he spent nearly a week denigrating a former Miss Universe.",
                List.of(), NAMES, "Obama"));
        assertEquals(null, ClaimChecker.problem(
                "Electric rates are up, food prices are up, health care costs have gone up by $2,500 a family.",
                "Electric rates and food prices have increased, and health care costs have gone up by $2,500 a family.",
                List.of(), NAMES, "Governor Romney"));
        assertEquals(null, ClaimChecker.problem(
                "In spite of his policies, all of the increase in natural gas and oil has happened on private land, not on government land.",
                "The increase in natural gas and oil production has occurred on private land, not on government land.",
                List.of("We have increased oil production to the highest levels in 16 years."), NAMES, "Governor Romney"));
        assertEquals(null, ClaimChecker.problem(
                "One, get us energy independent, North American energy independent. That creates about 4 million jobs.",
                "Achieving North American energy independence creates approximately 4 million jobs.",
                List.of(), NAMES, "Governor Romney"));
        // Still new: a cause nobody gave, and a different word that starts alike.
        assertNotNull(problem("We're putting a lot of people out of work.", "The investment caused people to lose their jobs."));
        assertNotNull(problem("He signed the contract in 2015.", "He contradicted himself in 2015."));
    }

    @Test
    void aFalseStartIsNotTurnedIntoAChoice() {
        // session-bc26c6a3: passed, and said that nobody's taxes go up.
        assertEquals("adds an \"or\" that was not said", ClaimChecker.problem(
                "If you make more if you make less than a quarter million dollars a year, then you will not see your income tax go up, your capital gains tax go up, your payroll tax, not one dime.",
                "If you make more than a quarter million dollars a year, or less than a quarter million dollars a year, your income tax, capital gains tax, and payroll tax will not go up.",
                List.of(), NAMES, "Obama"));
    }

    @Test
    void aClaimLeftAsSaidLosesTheWayIn() {
        // session-bc26c6a3, cards shown as said.
        assertEquals("The $750 billion rescue package, if it's structured properly, means that ultimately taxpayers get their money back.",
                ClaimChecker.asSaid("Well, first of all, I think it's important for the American public to understand that the $750 billion rescue package, if it's structured properly, means that ultimately taxpayers get their money back.", NAMES));
        assertEquals("I have been a strong proponent of pay as you go.",
                ClaimChecker.asSaid("What I want to emphasize, though is that I have been a strong proponent of pay as you go.", NAMES));
        assertEquals("Let's help families right away by providing them a tax cut.",
                ClaimChecker.asSaid("Number two, let's help families right away by providing them a tax cut.", NAMES));
        assertEquals("The small businesses that we're talking about would receive an increase in their taxes right now.",
                ClaimChecker.asSaid("And by the way, the small businesses that we're talking about would receive an increase in their taxes right now.", NAMES));
        assertEquals("In order to give additional tax cuts to Joe the plumber before he could make $250,000.",
                ClaimChecker.asSaid("In order to give, in order to give additional tax cuts to Joe the plumber before he could make $250,000.", NAMES));
        assertEquals("If you make less than a quarter million dollars a year, then you will not see your income tax go up.",
                ClaimChecker.asSaid("If you make more if you make less than a quarter million dollars a year, then you will not see your income tax go up.", NAMES));
        assertEquals("Warren Buffett could afford to pay a little more in taxes. In order to give additional tax cuts to Joe the plumber.",
                ClaimChecker.asSaid("Warren Buffett could afford to pay a little more in taxes. In order to give, in order to give additional tax cuts to Joe the plumber.", NAMES));
        assertEquals("We need to cut taxes, we need to cut spending.",
                ClaimChecker.asSaid("We need to cut taxes, we need to cut spending.", NAMES));
        // Not a way in: these are the claim.
        assertEquals("Second highest tax rate of anywhere in the world.",
                ClaimChecker.asSaid("Second highest tax rate of anywhere in the world.", NAMES));
        assertEquals("I think tax policy is a major difference between us.",
                ClaimChecker.asSaid("I think tax policy is a major difference between us.", NAMES));
    }

    @Test
    void aPronounTheSpeakerUsedIsLeftAlone() {
        assertEquals(null, problem("And he hasn't been able to identify them.", "He hasn't been able to identify them."));
        assertEquals(null, problem("I don't have a $5 trillion tax cut.", "Governor Romney does not have a $5 trillion tax cut."));
    }
}
