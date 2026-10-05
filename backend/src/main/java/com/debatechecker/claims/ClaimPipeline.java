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
     * @param claim      the sentence rewritten to stand alone, or the sentence as it was said
     * @param rewritten  false if the LLM's rewrite failed {@link ClaimChecker} (or the LLM failed) and
     *                   {@code claim} is the speaker's own words, which may need the lines before it
     * @param speakerName who said it, as far as known ("Donald Trump", else the label "S2"): a claim
     *                   left as said still says "I" and "my"
     * @param latencyMs  from the end of the sentence's audio to the claim being ready
     * @param id         counts up within the session; the verdict that follows carries the same id
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
    /** Only touched on the {@link #llm} thread. */
    private int claimsSoFar;
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
        synchronized (recent) {
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
        if (changedNames != null) listener.speakers(changedNames);
        if (saidByNewVoice != null) listener.newSpeaker(s.speaker(), saidByNewVoice, guess);
        listener.sentence(s, verdict);
        if (rewriter == null || verdict.category() != ClaimGate.Category.FACT_CLAIM
                || s.text().trim().split("\\s+").length < MIN_CLAIM_WORDS) {
            return;
        }
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
                    claim = ClaimChecker.tidy(rewriter.rewrite(List.copyOf(people), lines, speaker, s.text()), known);
                    if (claim.isEmpty()) {
                        log.info("[{}] no claim in: {}", sessionId, s.text());
                        return;
                    }
                    List<String> earlier = new ArrayList<>();
                    for (Sentence c : context) earlier.add(c.text());
                    String problem = ClaimChecker.problem(s.text(), claim, earlier, known);
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
                listener.claim(new Claim(s, claim, rewritten, speaker, latencyMs, ++claimsSoFar));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Waits for the sentences already queued, so the last claims of a session are not lost. */
    @Override
    public void close() {
        llm.shutdown();
        try {
            if (!llm.awaitTermination(15, TimeUnit.SECONDS)) llm.shutdownNow();
        } catch (InterruptedException e) {
            llm.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
