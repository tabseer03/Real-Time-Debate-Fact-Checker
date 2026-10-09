package com.debatechecker.factcheck;

import com.debatechecker.claims.ClaimPipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    /** The judge's one request for the minute is given this long by its own timeout. */
    private static final int MAX_JUDGE_SECONDS = 25;

    /**
     * @param quote  for a verdict the judge reached from a passage: the words it rests on (they are
     *               in {@code result.sources().get(0)}). With "could not be verified": the closest
     *               thing the judge found, for the viewer to compare. "" otherwise
     * @param reason the judge's one sentence for the viewer, or ""
     * @param kind   FACT unless the judge read the claim as an opinion or as no statement at all;
     *               those have no verdict and are shown apart from the claims
     */
    public record Statement(ClaimPipeline.Claim claim, FactChecker.Result result, String quote, String reason,
                            GeminiJudge.Kind kind) {}

    /**
     * @param speakers one entry per speaker who made a claim, in the order they first spoke
     * @param judgeFailed the judge was asked and did not answer (busy, or too slow): what it would
     *                 have read is reported as "check unavailable", not as "could not be verified"
     */
    public record Digest(double fromSec, double toSec, List<Speaker> speakers, boolean judgeFailed) {}

    /** @param name the speaker's name as last known ("Donald Trump"), else the label ("S2") */
    public record Speaker(String label, String name, List<Statement> statements) {}

    public interface Listener {
        /** Called on the digest's own thread, minutes in order. A minute without claims is skipped. */
        void digest(Digest digest);
    }

    private final String sessionId;
    private final FactChecker checker;
    private final GeminiJudge judge;
    private volatile LocalDate debateDay;
    private final int windowSeconds;
    private final Listener listener;
    private final List<ClaimPipeline.Claim> waiting = new ArrayList<>();
    private final ExecutorService reports = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("digest-", 0).factory());
    private double windowStart;
    private double heardUpTo;

    public MinuteDigest(String sessionId, FactChecker checker, int windowSeconds, Listener listener) {
        this(sessionId, checker, null, null, windowSeconds, listener);
    }

    /**
     * @param judge     reads the web passages of claims no fact-checker has rated; null for no judge
     * @param debateDay when the debate took place; null for today (a live debate)
     */
    public MinuteDigest(String sessionId, FactChecker checker, GeminiJudge judge, LocalDate debateDay,
                        int windowSeconds, Listener listener) {
        this.sessionId = sessionId;
        this.checker = checker;
        this.judge = judge != null && judge.enabled() ? judge : null;
        this.debateDay = debateDay;
        this.windowSeconds = windowSeconds;
        this.listener = listener;
    }

    /**
     * The day the debate was held, once it is known to be an old one (the server learns it from the
     * video's description after the session has opened). From then on passages are looked for among
     * what was published before that day, and the judge is told that date.
     */
    public void setDebateDay(LocalDate day) {
        debateDay = day;
    }

    /** Every sentence, claim or not: this is the clock. */
    public synchronized void heard(double audioEndSec) {
        heardUpTo = Math.max(heardUpTo, audioEndSec);
        while (heardUpTo >= windowStart + windowSeconds + GRACE_SECONDS) {
            report(windowStart + windowSeconds);
        }
    }

    /** A claim with the id of one still waiting replaces it: the speaker's next sentence was added to it. */
    public synchronized void add(ClaimPipeline.Claim claim) {
        waiting.removeIf(c -> c.id() == claim.id());
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
        claims.sort((a, b) -> Double.compare(a.sentence().audioStartSec(), b.sentence().audioStartSec()));
        List<ClaimPipeline.Claim> worthChecking = new ArrayList<>();
        for (ClaimPipeline.Claim c : claims) {
            String why = notCheckable(c, claims);
            if (why == null) worthChecking.add(c);
            else log.info("[{}] not checked ({}): {}", sessionId, why, c.claim());
        }
        if (worthChecking.isEmpty()) return;
        reports.execute(() -> listener.digest(check(from, end, worthChecking)));
    }

    /** A claim that opens with one of these hangs on the sentence before it. */
    private static final Set<String> CONTINUES = Set.of("because", "where", "which", "instead", "and", "but", "or", "so");
    /** Opening a claim, these are still whatever the lines before it were about: the rewrite did not resolve them. */
    private static final Set<String> POINTS_BACK = Set.of("it", "that", "this", "they", "he", "she", "those", "these");
    /** Fine with a main clause after a comma ("When I was governor, ..."), a loose end without one. */
    private static final Set<String> SUBORDINATE = Set.of("when", "if", "while", "although", "unless");
    /** "Of 11 million homes or more so that they can afford ..." is the end of the sentence before. "Of course" is not. */
    private static final java.util.regex.Pattern MID_SENTENCE = java.util.regex.Pattern.compile(
            "^of (?!course\\b|the\\b|all\\b|those\\b|these\\b)", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final double SAME_WORDS = 0.8;
    /** "This year", "this country": the here and now of the debate, not something from the line before. */
    private static final Set<String> HERE_AND_NOW = Set.of("year", "year's", "week", "week's", "month", "month's",
            "morning", "evening", "country", "country's", "nation", "nation's", "election", "campaign", "administration",
            "administration's", "president", "president's", "congress", "economy", "decade", "century");

    /**
     * "This year's deficit will reach a record high of $455 billion." was left out as an unresolved
     * "this" (live, 2026-10-08). Only for these nouns: "That starts with Secretary Clinton." and
     * "This plan ..." still hang on what was said before.
     */
    private static boolean namesItsThing(String first, String text) {
        if (!first.equals("this") && !first.equals("these")) return false;
        String[] words = text.split("[^A-Za-z']+", 3);
        return words.length > 1 && HERE_AND_NOW.contains(words[1].toLowerCase());
    }

    /**
     * Why a claim is left out of the minute's check, or null. One argument reaches us as up to nine
     * pieces ("Two and a half trillion.", "That starts with Secretary Clinton."), and a search for a
     * piece finds nothing. The pieces are dropped by rule, not joined by the LLM: gemma3:4b joined
     * them into things nobody said, out of words that were all said ("Mr. Trump has not released his
     * tax returns because nominees have released their returns for decades").
     *
     * @param minute everything claimed in the same minute, for spotting a claim said twice
     */
    static String notCheckable(ClaimPipeline.Claim claim, List<ClaimPipeline.Claim> minute) {
        String text = claim.claim().trim();
        List<String> content = Words.content(text);
        if (content.size() < 2) return "too short";
        if (Words.onlyFigures(content)) return "only a figure";
        if (text.endsWith("...") || !text.matches(".*[.!?][\"')]*")) return "unfinished";
        // "That's 16 million jobs in America." and "He's been watching some ads ..." open with
        // "that" and "he" as much as "That is ..." does.
        String first = text.split("[^A-Za-z']+", 2)[0].toLowerCase().replaceFirst("'(?:s|re|d|ll|ve)$", "");
        if (CONTINUES.contains(first) || SUBORDINATE.contains(first) && !text.contains(",")) return "continues another";
        if (MID_SENTENCE.matcher(text).find()) return "continues another";
        if (POINTS_BACK.contains(first) && !namesItsThing(first, text)) return "\"" + first + "\" is not said";
        Set<String> words = Set.copyOf(content);
        for (ClaimPipeline.Claim other : minute) {
            if (other == claim || !other.sentence().speaker().equals(claim.sentence().speaker())) continue;
            Set<String> theirs = Words.contentSet(other.claim());
            boolean fuller = theirs.size() > words.size()
                    || theirs.size() == words.size() && other.sentence().audioStartSec() > claim.sentence().audioStartSec();
            if (fuller && Words.covered(words, theirs) >= SAME_WORDS && Words.couldBeSame(text, other.claim())) {
                return "said again more fully";
            }
        }
        return null;
    }

    private Digest check(double from, double to, List<ClaimPipeline.Claim> claims) {
        FactChecker.Result[] results = new FactChecker.Result[claims.size()];
        CountDownLatch done = new CountDownLatch(claims.size());
        for (int i = 0; i < claims.size(); i++) {
            ClaimPipeline.Claim c = claims.get(i);
            int at = i;
            checker.check(FactChecker.query(c.claim(), c.rewritten(), c.speakerName()), debateDay, r -> {
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
        for (int i = 0; i < results.length; i++) {
            if (results[i] == null) {
                results[i] = new FactChecker.Result(FactChecker.Verdict.UNVERIFIABLE, "", List.of(), false, 0);
            }
        }
        GeminiJudge.Ruling[] rulings = judge(from, to, claims, results);
        boolean judgeFailed = rulings == null;
        if (judgeFailed) rulings = new GeminiJudge.Ruling[claims.size()];
        Map<String, List<Statement>> bySpeaker = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (int i = 0; i < claims.size(); i++) {
            ClaimPipeline.Claim c = claims.get(i);
            FactChecker.Result r = results[i];
            Statement statement = new Statement(c, r, "", "", GeminiJudge.Kind.FACT);
            if (rulings[i] != null && rulings[i].kind() != GeminiJudge.Kind.FACT) {
                statement = new Statement(c, r, "", rulings[i].reason(), rulings[i].kind());
            } else if (rulings[i] != null) {
                // The passage the verdict rests on goes first, where a fact-checker's review would be.
                List<Evidence> sources = new ArrayList<>(r.sources());
                sources.remove(rulings[i].source());
                sources.add(0, rulings[i].source());
                statement = new Statement(c, new FactChecker.Result(rulings[i].verdict(), "", sources, r.repeated(),
                        r.tookMs()), rulings[i].quote(), rulings[i].reason(), GeminiJudge.Kind.FACT);
            }
            bySpeaker.computeIfAbsent(c.sentence().speaker(), k -> new ArrayList<>()).add(statement);
            names.put(c.sentence().speaker(), c.speakerName());     // the latest: a name may be learned mid-minute
        }
        List<Speaker> speakers = new ArrayList<>();
        bySpeaker.forEach((label, statements) -> speakers.add(new Speaker(label, names.get(label), statements)));
        return new Digest(from, to, speakers, judgeFailed);
    }

    /**
     * One request for the whole minute: every claim no fact-checker has rated, with the web
     * passages found for it. A claim without any is sent too: the judge also says whether it is a
     * statement of fact at all.
     *
     * @return null if the judge was asked and gave no answer
     */
    private GeminiJudge.Ruling[] judge(double from, double to, List<ClaimPipeline.Claim> claims,
                                       FactChecker.Result[] results) {
        GeminiJudge.Ruling[] rulings = new GeminiJudge.Ruling[claims.size()];
        if (judge == null) return rulings;
        List<Integer> asked = new ArrayList<>();
        List<GeminiJudge.Case> cases = new ArrayList<>();
        for (int i = 0; i < claims.size(); i++) {
            FactChecker.Result r = results[i];
            // Wikipedia passages are picked by shared words and were never good enough to judge from.
            boolean webPassages = !r.sources().isEmpty()
                    && r.sources().stream().allMatch(e -> e.source().equals(ExaEvidence.SOURCE));
            if (r.verdict() != FactChecker.Verdict.UNVERIFIABLE) continue;
            ClaimPipeline.Claim c = claims.get(i);
            asked.add(i);
            // A voice without a name yet is "S2", which tells the judge nothing.
            String speaker = c.speakerName().matches("S\\d+") ? "" : c.speakerName();
            cases.add(new GeminiJudge.Case(speaker, c.claim(), webPassages ? r.sources() : List.of()));
        }
        if (cases.isEmpty()) return rulings;
        long start = System.nanoTime();
        try {
            List<GeminiJudge.Ruling> answers = judge.judge(debateDay != null ? debateDay : LocalDate.now(), cases);
            int given = 0, close = 0, notFacts = 0;
            for (int k = 0; k < asked.size(); k++) {
                rulings[asked.get(k)] = answers.get(k);
                if (answers.get(k) == null) continue;
                if (answers.get(k).kind() != GeminiJudge.Kind.FACT) notFacts++;
                else if (answers.get(k).verdict() == FactChecker.Verdict.UNVERIFIABLE) close++;
                else given++;
            }
            log.info("[{}] judge: {} verdicts, {} with something close and {} not statements of fact for {} claims"
                            + " of {}-{} s in {} ms", sessionId, given, close, notFacts, cases.size(), (int) from, (int) to,
                    (System.nanoTime() - start) / 1_000_000);
        } catch (IOException e) {
            log.warn("[{}] no judge for {}-{} s: {}", sessionId, (int) from, (int) to, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return rulings;
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

    /** The same for a statement in the report: one the judge did not read as a statement of fact has no verdict. */
    public static String status(Statement st) {
        return switch (st.kind()) {
            case OPINION -> "opinion";
            case NOT_A_STATEMENT -> "not a claim";
            case FACT -> status(st.result().verdict());
        };
    }

    /**
     * The same, knowing whether the judge answered for this window. Without it nothing was read:
     * "could not be verified" would say more than is known.
     */
    public static String status(Digest d, Statement st) {
        boolean unread = d.judgeFailed() && st.kind() == GeminiJudge.Kind.FACT
                && st.result().verdict() == FactChecker.Verdict.UNVERIFIABLE && st.result().rating().isEmpty();
        return unread ? "check unavailable" : status(st);
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
                boolean unsettled = r.verdict() == FactChecker.Verdict.UNVERIFIABLE;
                String source = r.sources().isEmpty() || unsettled && st.quote().isEmpty() ? ""
                        : " — " + (unsettled ? "closest found: " : "")
                        + (st.quote().isEmpty() ? "" : "\"" + st.quote() + "\" — ") + r.sources().get(0).title()
                        + (r.rating().isEmpty() ? "" : ", rated \"" + r.rating() + "\"");
                out.add("    [" + status(d, st) + "] " + st.claim().claim() + source);
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
            if (!reports.awaitTermination(MAX_CHECK_SECONDS + MAX_JUDGE_SECONDS + 5, TimeUnit.SECONDS)) {
                reports.shutdownNow();
            }
        } catch (InterruptedException e) {
            reports.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
