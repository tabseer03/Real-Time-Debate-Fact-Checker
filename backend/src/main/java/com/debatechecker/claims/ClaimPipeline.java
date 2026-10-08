package com.debatechecker.claims;

import com.debatechecker.speech.Sentence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Per session: takes each finished sentence, runs the claim gate on it straight away, and queues
 * the ones that pass for the LLM to rewrite. Rewriting happens on its own thread, one sentence at
 * a time (the GPU runs one request at a time anyway), so it never holds up transcription.
 * Also keeps track of who the voices are, so the LLM can turn "you" and "I" into names.
 */
public final class ClaimPipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClaimPipeline.class);

    /** Earlier lines given to the LLM. More made a 3B model rewrite the context instead of the sentence. */
    private static final int CONTEXT_LINES = 4;
    /** "You've proposed", "It's the single": crosstalk leftovers the gate lets through. */
    private static final int MIN_CLAIM_WORDS = 4;
    /**
     * A new voice is announced once it has said this much: enough for the user to recognise who
     * it is, and a stray fragment given to a bogus voice never asks.
     */
    private static final int MIN_WORDS_TO_ASK = 6;
    /**
     * A moderator's sentence is a claim only if the gate calls it check-worthy with at least this
     * probability; "factual at all" is enough for a debater. Moderators mostly state housekeeping
     * ("You have up to two minutes."), which the gate scores as factual but unimportant.
     */
    private static final double MODERATOR_MIN_CHECK_WORTHY = 0.5;
    /**
     * After "she said, I've been out of work since May." the next sentences may still be that
     * person talking: this many of them, within this long, are not the speaker's claims if they
     * say "I" or "we". ("Governor Romney's most recent job was lost, and Governor Romney and Ann
     * lost their home." was the woman at the rally speaking of her husband.)
     */
    private static final int QUOTE_SENTENCES = 3;
    private static final double QUOTE_SECONDS = 20;
    /**
     * A claim is made of up to this many of a speaker's sentences in a row (user, 2026-10-08): one
     * that is very short or cut off waits for the next, and one that repeats or carries on the
     * claim before it is added to that claim. Sentence by sentence, "Last year we had an almost
     * $800 billion trade deficit." and "In other words, trading with other countries, we had an
     * $800 billion deficit." were two claims with two different verdicts, and "What we all saw and
     * heard on Friday" / "Was Donald talking about women." were two pieces of nothing.
     */
    private static final int MAX_SENTENCES_IN_CLAIM = 3;
    /** Sentences further apart than this (audio time) are not taken together. */
    private static final double MAX_GAP_SECONDS = 4;
    /** Under this many words a claim is "very short". */
    private static final int SHORT_WORDS = 6;
    /** A sentence repeats the claim before it if this share of its content words is already in it. */
    private static final double REPEATS = 0.6;
    /**
     * A sentence the gate scores under this is not a claim by itself; it only goes along with a
     * stronger one next to it (user, 2026-10-08: one-line sentences were shown as claims). Live,
     * "This is who Donald Trump is." 0.09, "I agree with everything she said." 0.06 and "This is
     * like medieval times." 0.18 were cards; the real claims of that stretch scored 0.85 and up.
     * On the 2016 test set a bar of 0.3 keeps 86.3% of check-worthy sentences alone (the gate's
     * own 0.0506 keeps 94.2%) and passes 43.8% of all sentences instead of 56.9%.
     */
    private static final double STRONG_FACTUAL = 0.3;
    /** A claim that says again what the speaker claimed within this long is not shown a second time. */
    private static final double SAID_BEFORE_SECONDS = 90;
    private static final int REMEMBERED_CLAIMS = 8;

    /**
     * @param sentence   what was said: one sentence, or up to three taken together (their text
     *                   joined, from the start of the first to the end of the last)
     * @param claim      the sentence rewritten to stand alone, or the sentence as it was said
     * @param rewritten  false if the LLM's rewrite failed {@link ClaimChecker} (or the LLM failed) and
     *                   {@code claim} is the speaker's own words, which may need the lines before it
     * @param speakerName who said it, as far as known ("Donald Trump", else the label "S2"): a claim
     *                   left as said still says "I" and "my"
     * @param latencyMs  from the end of the sentence's audio to the claim being ready
     * @param id         counts up within the session; the verdict that follows carries the same id.
     *                   A claim sent again with an id already used replaces that claim: the
     *                   speaker's next sentence was added to it
     */
    public record Claim(Sentence sentence, String claim, boolean rewritten, String speakerName, long latencyMs,
                        int id) {}

    public interface Listener {
        /** Every sentence, with the gate's verdict. Called on the speaker's thread. */
        void sentence(Sentence sentence, ClaimGate.Result verdict);

        /** A rewritten claim, some hundreds of milliseconds after its sentence. */
        void claim(Claim claim);

        /** Who the voices are, whenever that changes: label ("S2") to name. Unnamed voices are left out. */
        void speakers(Map<String, String> names);

        /**
         * A voice not heard before, for asking the user who it is.
         * @param said   what it has said so far
         * @param guess  the name worked out for it already, or "" (usually "")
         */
        void newSpeaker(String speaker, String said, String guess);
    }

    private final String sessionId;
    private final ClaimGate gate;
    private final ClaimRewriter rewriter;
    private final Listener listener;
    private final SpeakerNames names = new SpeakerNames();
    private final Deque<Sentence> recent = new ArrayDeque<>();
    private final Map<String, StringBuilder> notYetAnnounced = new HashMap<>();
    private final Set<String> announced = new HashSet<>();
    /** Speaker label -> {audio time until which a quoted story may run, sentences of it left}. */
    private final Map<String, double[]> quoting = new HashMap<>();
    /** Speaker label -> the claim that speaker's latest sentences went into. Guarded by {@link #recent}. */
    private final Map<String, Unit> units = new HashMap<>();
    /** Speaker label -> that speaker's latest claims handed to the LLM, oldest first. Guarded by {@link #recent}. */
    private final Map<String, Deque<Unit>> sent = new HashMap<>();
    /** Only touched on the {@link #llm} thread. */
    private int claimsSoFar;

    /** One speaker's sentences that make one claim. */
    private static final class Unit {
        final List<Sentence> sentences = new ArrayList<>();
        /** The lines before the first sentence, for the LLM. */
        final List<Sentence> context;
        /** One of the sentences passed the gate by itself. */
        boolean anyFact;
        /** One of them scored {@link #STRONG_FACTUAL} or more: only then is the unit a claim. */
        boolean anyStrong;
        /** The best gate score among them, for the log. */
        double best;
        /** Handed to the LLM at least once. */
        boolean queued;
        /** One of them goes into somebody else's words: not rewritten. */
        boolean quotes;
        /** Too short or cut off to be sent on yet. */
        boolean waiting;
        long acceptedNanos;
        /** The id it was sent with, 0 before that. Only touched on the {@link #llm} thread. */
        int id;

        Unit(List<Sentence> context) {
            this.context = context;
        }

        Sentence last() {
            return sentences.get(sentences.size() - 1);
        }

        String text() {
            StringBuilder out = new StringBuilder();
            for (Sentence s : sentences) out.append(out.isEmpty() ? "" : " ").append(s.text().trim());
            return out.toString();
        }
    }
    private final ExecutorService llm = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("claims-", 0).factory());

    /** @param rewriter null to only classify */
    public ClaimPipeline(String sessionId, ClaimGate gate, ClaimRewriter rewriter, Listener listener) {
        this.sessionId = sessionId;
        this.gate = gate;
        this.rewriter = rewriter;
        this.listener = listener;
    }

    /** The video's title, if known: turns "Mr. Trump" into "Donald Trump". */
    public void setTitle(String title) {
        names.setTitle(title);
    }

    /** The user's answer to {@link Listener#newSpeaker}. Their name for a voice replaces any worked-out one. */
    public void nameSpeaker(String speaker, String name, boolean moderator) {
        Map<String, String> roster;
        synchronized (recent) {
            names.setByUser(speaker, name, moderator);
            roster = names.roster();
        }
        listener.speakers(roster);
    }

    /** Called from several speaker threads. */
    public void accept(Sentence s) {
        ClaimGate.Result gateVerdict = gate.classify(s.text());
        long acceptedNanos = System.nanoTime();
        List<Sentence> context;
        Map<String, String> changedNames = null;
        String saidByNewVoice = null, guess = "";
        boolean opensQuote = NonClaims.opensQuote(s.text()), insideQuote;
        synchronized (recent) {
            double[] quote = quoting.get(s.speaker());
            insideQuote = quote != null && quote[1]-- > 0 && s.audioStartSec() <= quote[0];
            if (opensQuote) quoting.put(s.speaker(), new double[] {s.audioEndSec() + QUOTE_SECONDS, QUOTE_SENTENCES});
            context = List.copyOf(recent);
            recent.addLast(s);
            while (recent.size() > CONTEXT_LINES) recent.removeFirst();
            if (names.observe(s.speaker(), s.text(), s.audioStartSec())) changedNames = names.roster();
            if (!announced.contains(s.speaker()) && !names.namedByUser(s.speaker())) {
                StringBuilder said = notYetAnnounced.computeIfAbsent(s.speaker(), k -> new StringBuilder());
                said.append(said.isEmpty() ? "" : " ").append(s.text());
                if (said.toString().trim().split("\\s+").length >= MIN_WORDS_TO_ASK) {
                    announced.add(s.speaker());
                    notYetAnnounced.remove(s.speaker());
                    saidByNewVoice = said.toString();
                    String worked = names.display(s.speaker());
                    if (!worked.equals(s.speaker())) guess = worked;
                }
            }
        }
        ClaimGate.Result verdict = gateVerdict;
        if (verdict.category() == ClaimGate.Category.FACT_CLAIM && names.isModerator(s.speaker())
                && verdict.checkWorthy() < MODERATOR_MIN_CHECK_WORTHY) {
            verdict = new ClaimGate.Result(ClaimGate.Category.JUNK, verdict.factual(), verdict.checkWorthy());
        }
        String why = insideQuote && NonClaims.firstPerson(s.text()) ? "someone else's words, quoted"
                : NonClaims.inSentence(s.text());
        if (why != null && verdict.category() == ClaimGate.Category.FACT_CLAIM) {
            log.info("[{}] not a claim ({}): {}", sessionId, why, s.text());
            verdict = new ClaimGate.Result(ClaimGate.Category.OPINION, verdict.factual(), verdict.checkWorthy());
        }
        if (changedNames != null) listener.speakers(changedNames);
        if (saidByNewVoice != null) listener.newSpeaker(s.speaker(), saidByNewVoice, guess);
        listener.sentence(s, verdict);
        if (rewriter == null) return;
        boolean fact = verdict.category() == ClaimGate.Category.FACT_CLAIM;
        boolean strong = fact && verdict.factual() >= STRONG_FACTUAL;
        double factual = verdict.factual();
        synchronized (recent) {
            // A speaker who has gone quiet is not going to finish the sentence that was waiting.
            for (Map.Entry<String, Unit> e : units.entrySet()) {
                Unit other = e.getValue();
                if (other.waiting && !e.getKey().equals(s.speaker())
                        && other.last().audioEndSec() + MAX_GAP_SECONDS < s.audioStartSec()) {
                    send(other);
                }
            }
            Unit unit = units.get(s.speaker());
            String text = s.text().trim();
            boolean joins = why == null && unit != null && unit.sentences.size() < MAX_SENTENCES_IN_CLAIM
                    && s.audioStartSec() - unit.last().audioEndSec() <= MAX_GAP_SECONDS
                    && (unit.waiting && (fact || unfinished(unit.last().text().trim()))
                            || Character.isLowerCase(text.charAt(0))
                            || fact && (words(text) < SHORT_WORDS || repeats(text, unit.text())
                                    || carriesOn(text, unit.text())));
            if (!joins) {
                if (unit != null && unit.waiting) send(unit);
                if (why != null) {
                    units.remove(s.speaker());
                    return;
                }
                unit = new Unit(context);
                units.put(s.speaker(), unit);
            }
            unit.sentences.add(s);
            unit.anyFact |= fact;
            unit.anyStrong |= strong;
            if (fact) unit.best = Math.max(unit.best, factual);
            unit.quotes |= opensQuote;
            unit.acceptedNanos = acceptedNanos;
            unit.waiting = unit.sentences.size() < MAX_SENTENCES_IN_CLAIM
                    && (unfinished(text) || unit.anyFact && (!unit.anyStrong || words(unit.text()) < SHORT_WORDS));
            if (!unit.waiting) send(unit);
        }
    }

    private static int words(String text) {
        return text.trim().split("\\s+").length;
    }

    /** Opening a sentence, these lean on the one before. */
    private static final Set<String> JOINS_ON = Set.of("and", "or", "but", "because", "so", "which");
    private static final Set<String> POINTS_AT = Set.of("it", "it's", "they", "they're", "he", "he's", "she", "she's",
            "that's", "those", "these");

    /**
     * "And he never apologized for the racist lie." after a claim about him, "It's just a
     * giveaway." after the claim about the subsidies (user, 2026-10-08): a sentence that opens
     * with "it" or "they" belongs to the claim before it, and one that opens with "and" / "or" /
     * "but" does if it is about the same thing, which here means sharing one content word.
     */
    private static boolean carriesOn(String sentence, String claimSoFar) {
        String first = sentence.split("[^A-Za-z']+", 2)[0].toLowerCase();
        if (POINTS_AT.contains(first)) return true;
        if (!JOINS_ON.contains(first)) return false;
        Set<String> shared = ClaimChecker.contentOf(sentence);
        shared.retainAll(ClaimChecker.contentOf(claimSoFar));
        return !shared.isEmpty();
    }

    /** "What we all saw and heard on Friday", "... and said, Ann,": more is coming. */
    private static boolean unfinished(String text) {
        return !text.matches(".*[.!?][\"')]*");
    }

    private static boolean repeats(String sentence, String claimSoFar) {
        return repeats(sentence, claimSoFar, 2);
    }

    /** @param minWords with fewer content words than this the sentence is too thin to say */
    private static boolean repeats(String sentence, String claimSoFar, int minWords) {
        Set<String> said = ClaimChecker.contentOf(sentence);
        if (said.size() < minWords) return false;
        Set<String> again = new HashSet<>(said);
        again.retainAll(ClaimChecker.contentOf(claimSoFar));
        return again.size() >= REPEATS * said.size();
    }

    private static final java.util.regex.Pattern FIGURE = java.util.regex.Pattern.compile("\\d+");

    /** The same words with another figure are another claim. */
    private static boolean sameFigures(String sentence, String earlier) {
        Set<String> theirs = new HashSet<>();
        for (java.util.regex.Matcher m = FIGURE.matcher(earlier); m.find(); ) theirs.add(m.group());
        for (java.util.regex.Matcher m = FIGURE.matcher(sentence); m.find(); ) {
            if (!theirs.contains(m.group())) return false;
        }
        return true;
    }

    /**
     * Hands the unit's sentences, as they are now, to the LLM as one claim. Called again when a
     * sentence has been added to a unit already sent: the claim then goes out again under its id.
     * Call with {@link #recent} held.
     */
    private void send(Unit unit) {
        unit.waiting = false;
        List<Sentence> members = List.copyOf(unit.sentences);
        Sentence first = members.get(0), last = unit.last();
        Sentence s = members.size() == 1 ? first
                : new Sentence(first.speaker(), unit.text(), first.audioStartSec(), last.audioEndSec(), last.latencyMs());
        boolean worth = unit.anyStrong;
        if (!worth && members.size() > 1) {
            // No sentence is enough alone; together they may be ("And housing." + "has begun to rise.").
            ClaimGate.Result joined = gate.classify(s.text());
            worth = joined.category() == ClaimGate.Category.FACT_CLAIM && joined.factual() >= STRONG_FACTUAL
                    && NonClaims.inSentence(s.text()) == null
                    && !(names.isModerator(s.speaker()) && joined.checkWorthy() < MODERATOR_MIN_CHECK_WORTHY);
        }
        if (!worth || words(s.text()) < MIN_CLAIM_WORDS) {
            if (unit.anyFact) {
                log.info("[{}] not a claim (too weak alone, {}): {}", sessionId, String.format("%.2f", unit.best), s.text());
            }
            return;
        }
        Deque<Unit> before = sent.computeIfAbsent(s.speaker(), k -> new ArrayDeque<>());
        if (!unit.queued) {
            for (Unit earlier : before) {
                if (earlier.last().audioEndSec() + SAID_BEFORE_SECONDS >= s.audioStartSec()
                        && repeats(s.text(), earlier.text(), 3) && sameFigures(s.text(), earlier.text())) {
                    log.info("[{}] not a claim (said before: \"{}\"): {}", sessionId, earlier.text(), s.text());
                    return;
                }
            }
            unit.queued = true;
            before.addLast(unit);
            while (before.size() > REMEMBERED_CLAIMS) before.removeFirst();
        }
        if (members.size() > 1) log.info("[{}] {} sentences taken together: {}", sessionId, members.size(), s.text());
        List<Sentence> context = unit.context;
        boolean opensQuote = unit.quotes;
        long acceptedNanos = unit.acceptedNanos;
        llm.execute(() -> {
            try {
                // Names are looked up now, not when the sentence arrived: they may be known by now.
                List<ClaimRewriter.Line> lines = new ArrayList<>();
                for (Sentence c : context) lines.add(new ClaimRewriter.Line(names.display(c.speaker()), c.text()));
                String speaker = names.display(s.speaker());
                Set<String> people = new LinkedHashSet<>(names.roster().values());
                for (ClaimRewriter.Line l : lines) people.add(l.speaker());
                people.add(speaker);
                Set<String> known = new LinkedHashSet<>(people);
                for (String p : people) known.add(p.replace(" (moderator)", ""));
                known.add(s.speaker());

                String claim;
                boolean rewritten = false;
                try {
                    // A sentence that quotes someone is left alone: the LLM pins the quoted "I" on the speaker.
                    claim = opensQuote ? ClaimChecker.tidy(s.text(), known)
                            : ClaimChecker.tidy(rewriter.rewrite(List.copyOf(people), lines, speaker, s.text()), known);
                    if (claim.isEmpty()) {
                        log.info("[{}] no claim in: {}", sessionId, s.text());
                        return;
                    }
                    // With a figure in it, there is something to check: the speaker's words are kept instead.
                    boolean position = NonClaims.inRewrite(claim, speaker);
                    if (position && !NonClaims.hasFigure(claim)) {
                        log.info("[{}] not a claim (the speaker's own feeling or position): {}", sessionId, claim);
                        return;
                    }
                    List<String> earlier = new ArrayList<>();
                    for (Sentence c : context) earlier.add(c.text());
                    String problem = opensQuote ? "quotes someone"
                            : position ? "wraps a figure in what the speaker believes"
                            : ClaimChecker.problem(s.text(), claim, earlier, known, speaker);
                    if (problem == null) {
                        rewritten = true;
                    } else {
                        log.info("[{}] rewrite rejected ({}): {}", sessionId, problem, claim);
                        claim = ClaimChecker.tidy(s.text(), known);
                    }
                } catch (IOException e) {
                    log.warn("[{}] could not rewrite \"{}\": {}", sessionId, s.text(), e.getMessage());
                    claim = ClaimChecker.tidy(s.text(), known);
                }
                long latencyMs = s.latencyMs() + (System.nanoTime() - acceptedNanos) / 1_000_000;
                if (unit.id == 0) unit.id = ++claimsSoFar;
                listener.claim(new Claim(s, claim, rewritten, speaker, latencyMs, unit.id));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Waits for the sentences already queued, so the last claims of a session are not lost. */
    @Override
    public void close() {
        synchronized (recent) {
            for (Unit unit : units.values()) if (unit.waiting) send(unit);
        }
        llm.shutdown();
        try {
            if (!llm.awaitTermination(15, TimeUnit.SECONDS)) llm.shutdownNow();
        } catch (InterruptedException e) {
            llm.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
