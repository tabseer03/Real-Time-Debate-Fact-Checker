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
}
