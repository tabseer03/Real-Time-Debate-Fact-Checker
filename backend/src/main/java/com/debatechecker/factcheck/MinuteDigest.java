package com.debatechecker.factcheck;

import com.debatechecker.claims.ClaimPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Per session: collects the claims of each minute of the debate, checks them together once the
 * minute is over, and reports them speaker by speaker — "in the last minute Donald Trump said
 * this and this; the first is confirmed, the second could not be verified".
 *
 * Checking by the minute instead of claim by claim (the user's idea, 2026-10-05) gives the check a
 * minute rather than two seconds, and puts a speaker's statements side by side.
 */
public final class MinuteDigest implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MinuteDigest.class);

    /**
     * A minute is closed this long after it ended (in audio time): a claim comes out of the
     * rewriting LLM up to 9 s after its sentence. A claim later than that is not lost, it goes
     * into the next digest.
     */
    private static final double GRACE_SECONDS = 10;
    private static final int MAX_CHECK_SECONDS = 20;

    public record Statement(ClaimPipeline.Claim claim, FactChecker.Result result) {}

    /**
     * @param speakers one entry per speaker who made a claim, in the order they first spoke
     */
    public record Digest(double fromSec, double toSec, List<Speaker> speakers) {}

    /** @param name the speaker's name as last known ("Donald Trump"), else the label ("S2") */
    public record Speaker(String label, String name, List<Statement> statements) {}

    public interface Listener {
        /** Called on the digest's own thread, minutes in order. A minute without claims is skipped. */
        void digest(Digest digest);
    }

    private final String sessionId;
    private final FactChecker checker;
    private final int windowSeconds;
    private final Listener listener;
    private final List<ClaimPipeline.Claim> waiting = new ArrayList<>();
    private final ExecutorService reports = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("digest-", 0).factory());
    private double windowStart;
    private double heardUpTo;

    public MinuteDigest(String sessionId, FactChecker checker, int windowSeconds, Listener listener) {
        this.sessionId = sessionId;
        this.checker = checker;
        this.windowSeconds = windowSeconds;
        this.listener = listener;
    }

    /** Every sentence, claim or not: this is the clock. */
    public synchronized void heard(double audioEndSec) {
        heardUpTo = Math.max(heardUpTo, audioEndSec);
        while (heardUpTo >= windowStart + windowSeconds + GRACE_SECONDS) {
            report(windowStart + windowSeconds);
        }
    }

    public synchronized void add(ClaimPipeline.Claim claim) {
        waiting.add(claim);
    }

    /** Closes the window ending at {@code end}: everything said before then that has arrived. */
    private void report(double end) {
        List<ClaimPipeline.Claim> claims = new ArrayList<>();
        for (Iterator<ClaimPipeline.Claim> it = waiting.iterator(); it.hasNext(); ) {
            ClaimPipeline.Claim c = it.next();
            if (c.sentence().audioEndSec() < end) {
                claims.add(c);
                it.remove();
            }
        }
        double from = windowStart;
        windowStart = end;
        if (claims.isEmpty()) return;
        claims.sort((a, b) -> Double.compare(a.sentence().audioStartSec(), b.sentence().audioStartSec()));
        reports.execute(() -> listener.digest(check(from, end, claims)));
    }

    private Digest check(double from, double to, List<ClaimPipeline.Claim> claims) {
        FactChecker.Result[] results = new FactChecker.Result[claims.size()];
        CountDownLatch done = new CountDownLatch(claims.size());
        for (int i = 0; i < claims.size(); i++) {
            ClaimPipeline.Claim c = claims.get(i);
            int at = i;
            checker.check(FactChecker.query(c.claim(), c.rewritten(), c.speakerName()), r -> {
                results[at] = r;
                done.countDown();
            });
        }
        try {
            if (!done.await(MAX_CHECK_SECONDS, TimeUnit.SECONDS)) {
                log.warn("[{}] {} of {} checks for {}-{} s did not finish in {} s", sessionId, done.getCount(),
                        claims.size(), (int) from, (int) to, MAX_CHECK_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Map<String, List<Statement>> bySpeaker = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (int i = 0; i < claims.size(); i++) {
            ClaimPipeline.Claim c = claims.get(i);
            FactChecker.Result r = results[i] != null ? results[i]
                    : new FactChecker.Result(FactChecker.Verdict.UNVERIFIABLE, "", List.of(), false, 0);
            bySpeaker.computeIfAbsent(c.sentence().speaker(), k -> new ArrayList<>()).add(new Statement(c, r));
            names.put(c.sentence().speaker(), c.speakerName());     // the latest: a name may be learned mid-minute
        }
        List<Speaker> speakers = new ArrayList<>();
        bySpeaker.forEach((label, statements) -> speakers.add(new Speaker(label, names.get(label), statements)));
        return new Digest(from, to, speakers);
    }

    /** What a verdict is called in the report. */
    public static String status(FactChecker.Verdict verdict) {
        return switch (verdict) {
            case TRUE -> "confirmed";
            case FALSE -> "contradicted";
            case MISLEADING -> "misleading";
            case UNVERIFIABLE -> "could not be verified";
        };
    }

    /** The digest as lines of text, for the log and for Replay. */
    public static List<String> describe(Digest d) {
        List<String> out = new ArrayList<>();
        out.add(String.format("%d:%02d-%d:%02d", (int) d.fromSec() / 60, (int) d.fromSec() % 60,
                (int) d.toSec() / 60, (int) d.toSec() % 60));
        for (Speaker s : d.speakers()) {
            out.add("  " + s.name() + " said:");
            for (Statement st : s.statements()) {
                FactChecker.Result r = st.result();
                String source = r.verdict() == FactChecker.Verdict.UNVERIFIABLE || r.sources().isEmpty() ? ""
                        : " — " + r.sources().get(0).title() + (r.rating().isEmpty() ? "" : ", rated \"" + r.rating() + "\"");
                out.add("    [" + status(r.verdict()) + "] " + st.claim().claim() + source);
            }
        }
        return out;
    }

    /** Reports whatever is left, then waits for the reports under way. */
    @Override
    public void close() {
        synchronized (this) {
            // Up to the end of what was heard, however short the last stretch is.
            while (!waiting.isEmpty()) report(Math.max(windowStart + windowSeconds, Math.nextUp(heardUpTo)));
        }
        reports.shutdown();
        try {
            if (!reports.awaitTermination(MAX_CHECK_SECONDS + 5, TimeUnit.SECONDS)) reports.shutdownNow();
        } catch (InterruptedException e) {
            reports.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
