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
     * @param claim      the sentence rewritten to stand alone
     * @param latencyMs  from the end of the sentence's audio to the claim being ready
     */
    public record Claim(Sentence sentence, String claim, long latencyMs) {}

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
        ClaimGate.Result verdict = gate.classify(s.text());
        long acceptedNanos = System.nanoTime();
        List<Sentence> context;
        Map<String, String> changedNames = null;
        String saidByNewVoice = null, guess = "";
        synchronized (recent) {
            context = List.copyOf(recent);
            recent.addLast(s);
            while (recent.size() > CONTEXT_LINES) recent.removeFirst();
            if (names.observe(s.speaker(), s.text())) changedNames = names.roster();
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
                String claim = rewriter.rewrite(List.copyOf(people), lines, speaker, s.text());
                long latencyMs = s.latencyMs() + (System.nanoTime() - acceptedNanos) / 1_000_000;
                if (claim.isEmpty()) {
                    log.info("[{}] no claim in: {}", sessionId, s.text());
                } else {
                    listener.claim(new Claim(s, claim, latencyMs));
                }
            } catch (IOException e) {
                log.warn("[{}] could not rewrite \"{}\": {}", sessionId, s.text(), e.getMessage());
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
