package com.debatechecker.factcheck;

import com.debatechecker.claims.ClaimPipeline;
import com.debatechecker.speech.Sentence;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Which claims land in which minute. No network: with no key and no Wikipedia every check is UNVERIFIABLE. */
class MinuteDigestTest {

    private final List<MinuteDigest.Digest> got = Collections.synchronizedList(new ArrayList<>());
    private final FactChecker checker = new FactChecker(new GoogleFactCheck("", Duration.ofSeconds(1)), null);
    private final MinuteDigest digest = new MinuteDigest("test", checker, 60, got::add);
    private int ids;

    private void say(String speaker, String name, double start, double end, String text, boolean claim) {
        Sentence s = new Sentence(speaker, text, start, end, 0);
        digest.heard(end);
        if (claim) digest.add(new ClaimPipeline.Claim(s, text, true, name, 0, ++ids));
    }

    @Test
    void claimsAreReportedByMinuteAndSpeaker() {
        say("S1", "S1", 5, 9, "We built 40 new hospitals.", true);
        say("S2", "Donald Trump", 20, 24, "Ford is leaving.", true);
        say("S1", "Hillary Clinton", 40, 44, "Nine million people lost their jobs.", true);   // named by now
        say("S2", "Donald Trump", 62, 66, "We owe 20 trillion dollars.", true);
        say("S0", "S0", 68, 69, "Thank you.", false);       // not yet 10 s past the minute: nothing reported
        assertEquals(0, got.size());
        say("S0", "S0", 70, 71, "Go ahead.", false);
        say("S0", "S0", 185, 186, "Next question.", false); // closes minute two; minute three has no claims
        digest.close();
        checker.close();

        assertEquals(2, got.size());
        MinuteDigest.Digest first = got.get(0);
        assertEquals(0, first.fromSec());
        assertEquals(60, first.toSec());
        assertEquals(List.of("Hillary Clinton", "Donald Trump"),
                first.speakers().stream().map(MinuteDigest.Speaker::name).toList());
        assertEquals(2, first.speakers().get(0).statements().size());
        assertEquals("Ford is leaving.", first.speakers().get(1).statements().get(0).claim().claim());
        assertEquals(60, got.get(1).fromSec());
        assertEquals("We owe 20 trillion dollars.", got.get(1).speakers().get(0).statements().get(0).claim().claim());
        assertEquals(List.of("0:00-1:00", "  Hillary Clinton said:",
                        "    [could not be verified] We built 40 new hospitals.",
                        "    [could not be verified] Nine million people lost their jobs.",
                        "  Donald Trump said:", "    [could not be verified] Ford is leaving."),
                MinuteDigest.describe(first));
    }

    @Test
    void aClaimThatArrivesLateGoesIntoTheNextReport() {
        digest.heard(75);                                    // minute one closes with nothing in it
        Sentence late = new Sentence("S1", "We built 40 new hospitals.", 50, 55, 0);
        digest.add(new ClaimPipeline.Claim(late, late.text(), true, "S1", 0, 1));
        digest.heard(131);
        digest.close();
        checker.close();
        assertEquals(1, got.size());
        assertEquals(60, got.get(0).fromSec());
        assertEquals(1, got.get(0).speakers().get(0).statements().size());
    }

    private static ClaimPipeline.Claim claim(String speaker, double start, String text) {
        return new ClaimPipeline.Claim(new Sentence(speaker, text, start, start + 3, 0), text, true, speaker, 0, 0);
    }

    /** Claims from session-c51d98d5 as the pipeline produced them. */
    @Test
    void piecesAndRepeatsAreNotChecked() {
        List<ClaimPipeline.Claim> minute = List.of(
                claim("S0", 4, "Secretary Clinton is calling for a tax increase in the wealthiest Americans."),
                claim("S0", 9, "Secretary Clinton is calling for a tax increase in the wealthiest Americans, and Mr. Trump is calling for tax cuts for the wealthy."),
                claim("S1", 35, "When these people put billions and billions of dollars into companies and bring two and a half trillion dollars back from overseas."),
                claim("S1", 55, "They are leaving our country and, believe it or not,..."),
                claim("S1", 61, "People are leaving because taxes are too high and because some of them have lots of money outside of our country."),
                claim("S1", 83, "Because we have a president that can't sit them around the table and get them to approve something."),
                claim("S1", 93, "Two and a half trillion."),
                claim("S1", 96, "It's probably $5 trillion that we can't bring into our country, Lester, and with a little leadership, you'd get it in here very quickly,"),
                claim("S1", 110, "There is no leadership."),
                claim("S1", 112, "That starts with Secretary Clinton."),
                claim("S2", 196, "Trickle down did not work."),
                claim("S2", 203, "Slashing taxes on the wealthy has not worked."),
                claim("S1", 302, "When they raise interest rates, you're going to see some very bad things happen because the Fed is not doing their job."),
                claim("S0", 312, "Mr. Trump has not released his tax returns."));
        List<String> kept = new ArrayList<>();
        for (ClaimPipeline.Claim c : minute) if (MinuteDigest.notCheckable(c, minute) == null) kept.add(c.claim());
        assertEquals(List.of(
                "Secretary Clinton is calling for a tax increase in the wealthiest Americans, and Mr. Trump is calling for tax cuts for the wealthy.",
                "People are leaving because taxes are too high and because some of them have lots of money outside of our country.",
                "Trickle down did not work.",
                "Slashing taxes on the wealthy has not worked.",
                "When they raise interest rates, you're going to see some very bad things happen because the Fed is not doing their job.",
                "Mr. Trump has not released his tax returns."), kept);
    }

    @Test
    void theSameWordsFromAnotherSpeakerOrWithAnotherFigureAreNotARepeat() {
        List<ClaimPipeline.Claim> minute = List.of(
                claim("S1", 5, "Unemployment fell to 5 percent last year."),
                claim("S2", 9, "Unemployment fell to 5 percent last year in this state."),
                claim("S1", 20, "Unemployment fell to 4 percent last year in this state."));
        for (ClaimPipeline.Claim c : minute) assertEquals(null, MinuteDigest.notCheckable(c, minute));
    }
}
