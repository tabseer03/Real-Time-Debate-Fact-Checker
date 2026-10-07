package com.debatechecker.factcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Checks a claim against fact-checks already published (Google Fact Check), whose rating becomes
 * the verdict; if there is none, passages from the web (or Wikipedia) are attached as related
 * reading and the verdict stays UNVERIFIABLE.
 *
 * There is deliberately no LLM verdict from the passages. gemma3:4b, the model the 4 GB
 * GPU has room for, was tried as the judge and is wrong too often to show its answer as a verdict
 * (see "Fact-checking" in CLAUDE.md).
 *
 * One instance for the whole server: lookups are network calls on their own threads, and the
 * answers are remembered across sessions.
 */
public final class FactChecker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FactChecker.class);

    public enum Verdict { TRUE, FALSE, MISLEADING, UNVERIFIABLE }

    /**
     * @param rating   the fact-checker's own words ("Pants on Fire") when the verdict comes from one, else ""
     * @param sources  what the verdict rests on, or for UNVERIFIABLE what there is to read; may be empty
     * @param repeated true if this claim was already checked and the answer was remembered
     * @param tookMs   time spent checking (0 or so for a remembered answer)
     */
    public record Result(Verdict verdict, String rating, List<Evidence> sources, boolean repeated, long tookMs) {}

    private static final int PASSAGES = 3;
    private static final int MAX_SOURCES = 3;
    private static final int REMEMBERED = 500;
    /**
     * Two claims are the same claim if they share this much of their content words (Jaccard) and
     * agree on figures and on whether they deny something.
     */
    private static final double SAME_CLAIM = 0.8;

    private static final Pattern RATING_FALSE = Pattern.compile(
            "\\b(?:false|pants on fire|incorrect|inaccurate|wrong|fake|not (?:true|accurate|correct|right)|untrue|no evidence|baseless|"
                    + "unfounded|debunked|hoax|four pinocchios|fabricated|bogus)\\b");
    private static final Pattern RATING_MIXED = Pattern.compile(
            "\\b(?:half true|half-true|mixed|mixture|misleading|missing context|lacks context|needs context|"
                    + "out of context|exaggerat\\w*|distort\\w*|partly|partially|spins? the facts|cherry.?pick\\w*|"
                    + "(?:one|two|three) pinocchios?|overstate[sd]?|understate[sd]?|not the whole story)\\b");
    private static final Pattern RATING_TRUE = Pattern.compile(
            "\\b(?:true|correct|accurate|right|confirmed|geppetto checkmark)\\b");

    private final GoogleFactCheck google;
    private final EvidenceSource evidence;
    private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();
    private record Remembered(String claim, LocalDate before, Set<String> words, Result result) {}

    /** Claim as looked up → its answer, least recently used first. */
    private final Map<String, Remembered> remembered = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Remembered> eldest) {
            return size() > REMEMBERED;
        }
    };

    /** @param evidence where to look when no fact-check has been published; null for nowhere */
    public FactChecker(GoogleFactCheck google, EvidenceSource evidence) {
        this.google = google;
        this.evidence = evidence;
        if (!google.enabled()) {
            log.warn("No Google Fact Check key (debatechecker.google-factcheck-key): claims get no verdict, "
                    + "only related passages.");
        }
    }

    /**
     * What to look up for a claim. A claim left in the speaker's own words still says "I" and
     * "my", so it is looked up with the speaker's name in front, if the name is known.
     */
    public static String query(String claim, boolean rewritten, String speakerName) {
        boolean named = !speakerName.matches("S\\d+") && !speakerName.contains("moderator");
        return rewritten || !named ? claim : speakerName + ": " + claim;
    }

    /** Returns at once; {@code done} is called exactly once, on another thread unless the answer is remembered. */
    public void check(String claim, Consumer<Result> done) {
        check(claim, null, done);
    }

    /**
     * @param before the day of the debate when an old one is being played: passages are then looked
     *               for among what was published before it. null for a live debate.
     */
    public void check(String claim, LocalDate before, Consumer<Result> done) {
        long start = System.nanoTime();
        Result known = recall(claim, before);
        if (known != null) {
            done.accept(new Result(known.verdict(), known.rating(), known.sources(), true, 0));
            return;
        }
        lookups.execute(() -> {
            Result result;
            try {
                result = look(claim, before, start);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result = new Result(Verdict.UNVERIFIABLE, "", List.of(), false, msSince(start));
            } catch (RuntimeException e) {
                log.warn("could not check \"{}\"", claim, e);
                result = new Result(Verdict.UNVERIFIABLE, "", List.of(), false, msSince(start));
            }
            done.accept(result);
        });
    }

    private Result look(String claim, LocalDate before, long start) throws InterruptedException {
        boolean complete = true;    // an answer cut short by a network error is not remembered
        // Both at once: Google has nothing for almost every debate claim (127 of 135 on the
        // 18-minute recording) and takes about a second to say so.
        Future<List<Evidence>> evidenceAnswer = evidence == null ? null
                : lookups.submit(() -> evidence.search(claim, PASSAGES, before));
        try {
            List<Evidence> reviews = google.search(claim);
            if (!reviews.isEmpty()) {
                Evidence best = reviews.get(0);
                Result r = new Result(verdictOf(best.rating()), best.rating(),
                        List.copyOf(reviews.subList(0, Math.min(MAX_SOURCES, reviews.size()))), false, msSince(start));
                if (evidenceAnswer != null) evidenceAnswer.cancel(true);
                remember(claim, before, r);
                return r;
            }
        } catch (IOException e) {
            log.warn("Google Fact Check failed for \"{}\": {}", claim, e.getMessage());
            complete = false;
        }
        List<Evidence> passages = List.of();
        if (evidenceAnswer != null) {
            try {
                passages = evidenceAnswer.get();
            } catch (ExecutionException e) {
                log.warn("Evidence search failed for \"{}\": {}", claim, e.getCause().getMessage());
                complete = false;
            }
        }
        Result r = new Result(Verdict.UNVERIFIABLE, "", passages, false, msSince(start));
        if (complete) remember(claim, before, r);
        return r;
    }

    /**
     * Fact-checkers word their ratings freely ("Mostly False", "Four Pinocchios", "Missing context").
     * Anything not recognised is UNVERIFIABLE; the rating itself is always passed on to be shown.
     */
    static Verdict verdictOf(String rating) {
        String r = rating.toLowerCase().replace('’', '\'');
        // "Half true" contains "true" and "not true" does too: mixed first, then false, then true.
        // "Mostly false" and "mostly true" count as false and true.
        if (RATING_MIXED.matcher(r).find()) return Verdict.MISLEADING;
        if (RATING_FALSE.matcher(r).find()) return Verdict.FALSE;
        if (RATING_TRUE.matcher(r).find()) return Verdict.TRUE;
        return Verdict.UNVERIFIABLE;
    }

    /** An answer found with one date limit is not an answer for another: the passages differ. */
    private Result recall(String claim, LocalDate before) {
        Set<String> words = Words.contentSet(claim);
        synchronized (remembered) {
            Remembered exact = remembered.get(before + " " + claim);
            if (exact != null) return exact.result();
            for (Map.Entry<String, Remembered> e : remembered.entrySet()) {
                if (!Objects.equals(before, e.getValue().before())) continue;
                Set<String> theirs = e.getValue().words();
                Set<String> both = new HashSet<>(words);
                both.retainAll(theirs);
                int either = words.size() + theirs.size() - both.size();
                if (either > 0 && (double) both.size() / either >= SAME_CLAIM
                        && Words.couldBeSame(claim, e.getValue().claim())) {
                    return e.getValue().result();
                }
            }
            return null;
        }
    }

    private void remember(String claim, LocalDate before, Result r) {
        synchronized (remembered) {
            remembered.put(before + " " + claim, new Remembered(claim, before, Words.contentSet(claim), r));
        }
    }

    private static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Lets lookups already under way finish, so the last verdicts of a session are not lost. */
    @Override
    public void close() {
        lookups.shutdown();
        try {
            if (!lookups.awaitTermination(15, TimeUnit.SECONDS)) lookups.shutdownNow();
        } catch (InterruptedException e) {
            lookups.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /** For {@link FactCheckTry}: the sources as lines of text. */
    static List<String> describe(Result r) {
        List<String> out = new ArrayList<>();
        for (Evidence e : r.sources()) {
            out.add((e.rating().isEmpty() ? "" : "[" + e.rating() + "] ") + e.title() + " — " + e.text()
                    + "  <" + e.url() + ">");
        }
        return out;
    }
}
