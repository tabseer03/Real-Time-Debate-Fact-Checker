package com.debatechecker.audio;

import com.debatechecker.claims.ClaimGate;
import com.debatechecker.claims.ClaimPipeline;
import com.debatechecker.claims.ClaimRewriter;
import com.debatechecker.factcheck.Evidence;
import com.debatechecker.factcheck.FactChecker;
import com.debatechecker.factcheck.DebateDate;
import com.debatechecker.factcheck.GeminiJudge;
import com.debatechecker.factcheck.MinuteDigest;
import com.debatechecker.speech.Sentence;
import com.debatechecker.speech.SessionPipeline;
import com.debatechecker.speech.SpeechModels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives 16 kHz mono Int16 PCM from the extension, runs it through the speech pipeline,
 * and sends each finished sentence back to the extension as JSON with the claim gate's verdict,
 * followed by the rewritten claim for the sentences that pass, and once a minute a digest: each
 * speaker's claims of that minute with what the fact-check found.
 */
@Component
public class AudioStreamHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioStreamHandler.class);

    private record Connection(AudioSession recording, SessionPipeline pipeline, ClaimPipeline claims,
                              MinuteDigest digest, WebSocketSession out) {}

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final SpeechModels models;
    private final ClaimGate gate;
    private final ClaimRewriter rewriter;
    private final FactChecker factChecker;
    private final GeminiJudge judge;
    private final ObjectMapper json = new ObjectMapper();
    private final String recordingsDir;
    private final float speakerThreshold;
    private final int maxSpeakers;
    private final int digestSeconds;

    public AudioStreamHandler(SpeechModels models, ClaimGate gate, ClaimRewriter rewriter, FactChecker factChecker,
                              GeminiJudge judge,
                              @Value("${debatechecker.recordings-dir}") String recordingsDir,
                              @Value("${debatechecker.speaker-threshold}") float speakerThreshold,
                              @Value("${debatechecker.max-speakers}") int maxSpeakers,
                              @Value("${debatechecker.digest-seconds}") int digestSeconds) {
        this.digestSeconds = digestSeconds;
        this.models = models;
        this.gate = gate;
        this.rewriter = rewriter;
        this.factChecker = factChecker;
        this.judge = judge;
        this.recordingsDir = recordingsDir;
        this.speakerThreshold = speakerThreshold;
        this.maxSpeakers = maxSpeakers;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession ws) throws IOException {
        String id = ws.getId().substring(0, 8);
        // Sentences arrive from several speaker threads; plain WebSocketSession isn't safe for
        // concurrent sends, the decorator serializes them.
        WebSocketSession out = new ConcurrentWebSocketSessionDecorator(ws, 5_000, 512 * 1024);
        // Live: the debate is today.
        MinuteDigest digest = new MinuteDigest(id, factChecker, judge, null, digestSeconds,
                d -> sendDigest(id, out, d));
        ClaimPipeline claims = new ClaimPipeline(id, gate, rewriter, new ClaimPipeline.Listener() {
            @Override
            public void sentence(Sentence sentence, ClaimGate.Result verdict) {
                sendSentence(id, out, sentence, verdict);
                digest.heard(sentence.audioEndSec());
            }

            @Override
            public void claim(ClaimPipeline.Claim claim) {
                // Shown at once, unchecked; its verdict comes in the digest of its minute, by its id.
                sendClaim(id, out, claim);
                digest.add(claim);
            }

            @Override
            public void speakers(Map<String, String> names) {
                log.info("[{}] SPEAKERS {}", id, names);
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("type", "speakers");
                msg.put("names", names);
                send(id, out, msg);
            }

            @Override
            public void newSpeaker(String speaker, String said, String guess) {
                log.info("[{}] NEW SPEAKER {}: {}", id, speaker, said);
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("type", "new-speaker");
                msg.put("speaker", speaker);
                msg.put("text", said);
                msg.put("guess", guess);
                send(id, out, msg);
            }
        });
        SessionPipeline pipeline = new SessionPipeline(id, models, speakerThreshold, maxSpeakers, claims::accept);
        connections.put(ws.getId(), new Connection(new AudioSession(id, recordingsDir), pipeline, claims, digest, out));
        log.info("[{}] connected", id);
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        log.info("[{}] control: {}", ws.getId().substring(0, 8), message.getPayload());
        Connection c = connections.get(ws.getId());
        if (c == null) return;
        try {
            JsonNode msg = json.readTree(message.getPayload());
            String type = msg.path("type").asText();
            if ("start".equals(type)) {
                c.claims().setTitle(msg.path("tabTitle").asText(""));
                // An old debate played as a test: look for evidence from before it, not from today.
                DebateDate.Found old = DebateDate.find(msg.path("description").asText(""),
                        msg.path("published").asText(""), msg.path("liveNow").asBoolean(false), LocalDate.now());
                String id = ws.getId().substring(0, 8);
                if (old == null) {
                    log.info("[{}] DEBATE DATE today (live, or the video does not say)", id);
                } else {
                    log.info("[{}] DEBATE DATE {} (from the {}): web search limited to pages before it",
                            id, old.day(), old.from());
                    c.digest().setDebateDay(old.day());
                }
                Map<String, Object> reply = new LinkedHashMap<>();
                reply.put("type", "debate-date");
                reply.put("date", old == null ? "" : old.day().toString());
                reply.put("from", old == null ? "" : old.from());
                send(id, c.out(), reply);
            } else if ("speaker-name".equals(type) && msg.path("speaker").asText().matches("S\\d{1,2}")) {
                // The user's answer to a "new-speaker" message.
                c.claims().nameSpeaker(msg.path("speaker").asText(), msg.path("name").asText(""),
                        msg.path("moderator").asBoolean(false));
            }
        } catch (IOException e) {
            log.warn("[{}] control message is not JSON", ws.getId().substring(0, 8));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession ws, BinaryMessage message) throws IOException {
        Connection c = connections.get(ws.getId());
        if (c == null) return;
        c.pipeline().feed(message.getPayload().duplicate());
        c.recording().onChunk(message.getPayload().duplicate());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) throws IOException {
        Connection c = connections.remove(ws.getId());
        if (c != null) {
            c.pipeline().close();
            c.recording().close();
            c.claims().close();
            c.digest().close();
        }
        log.info("[{}] closed: {}", ws.getId().substring(0, 8), status);
    }

    @Override
    public void handleTransportError(WebSocketSession ws, Throwable ex) {
        log.warn("[{}] transport error: {}", ws.getId().substring(0, 8), ex.getMessage());
    }

    private void sendSentence(String id, WebSocketSession out, Sentence s, ClaimGate.Result verdict) {
        log.info("[{}] SENTENCE [{}] ({} ms) {} {} {}", id, s.speaker(), s.latencyMs(), verdict.category(),
                String.format("%.2f", verdict.factual()), s.text());
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "sentence");
        msg.put("speaker", s.speaker());
        msg.put("text", s.text());
        msg.put("start", s.audioStartSec());
        msg.put("end", s.audioEndSec());
        msg.put("latencyMs", s.latencyMs());
        msg.put("category", verdict.category().name());
        msg.put("factual", verdict.factual());
        send(id, out, msg);
    }

    private void sendClaim(String id, WebSocketSession out, ClaimPipeline.Claim c) {
        log.info("[{}] CLAIM{} [{}] ({} ms) {}", id, c.rewritten() ? "" : " (as said)", c.sentence().speaker(),
                c.latencyMs(), c.claim());
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "claim");
        msg.put("id", c.id());
        msg.put("speaker", c.sentence().speaker());
        msg.put("claim", c.claim());
        msg.put("rewritten", c.rewritten());
        msg.put("speakerName", c.speakerName());
        msg.put("sentence", c.sentence().text());
        msg.put("start", c.sentence().audioStartSec());
        msg.put("end", c.sentence().audioEndSec());
        msg.put("latencyMs", c.latencyMs());
        send(id, out, msg);
    }

    /** One minute of the debate, speaker by speaker, each claim with what the check found. */
    private void sendDigest(String id, WebSocketSession out, MinuteDigest.Digest d) {
        for (String line : MinuteDigest.describe(d)) log.info("[{}] DIGEST {}", id, line);
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "digest");
        msg.put("from", d.fromSec());
        msg.put("to", d.toSec());
        msg.put("judgeFailed", d.judgeFailed());
        List<Map<String, Object>> speakers = new ArrayList<>();
        for (MinuteDigest.Speaker sp : d.speakers()) {
            List<Map<String, Object>> statements = new ArrayList<>();
            for (MinuteDigest.Statement st : sp.statements()) {
                FactChecker.Result r = st.result();
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("id", st.claim().id());
                s.put("claim", st.claim().claim());
                s.put("sentence", st.claim().sentence().text());
                s.put("start", st.claim().sentence().audioStartSec());
                s.put("verdict", r.verdict().name());
                s.put("status", MinuteDigest.status(d, st));
                // "opinion" and "none" are not claims after all: the page sets them apart.
                s.put("kind", switch (st.kind()) {
                    case FACT -> "fact";
                    case OPINION -> "opinion";
                    case NOT_A_STATEMENT -> "none";
                });
                s.put("rating", r.rating());
                // Set when the verdict was read from a web passage: the words it rests on (from sources[0]).
                s.put("quote", st.quote());
                s.put("reason", st.reason());
                List<Map<String, Object>> sources = new ArrayList<>();
                for (Evidence e : r.sources()) {
                    Map<String, Object> src = new LinkedHashMap<>();
                    src.put("source", e.source());
                    src.put("title", e.title());
                    src.put("url", e.url());
                    src.put("text", e.text());
                    src.put("rating", e.rating());
                    sources.add(src);
                }
                s.put("sources", sources);
                s.put("repeated", r.repeated());
                statements.add(s);
            }
            Map<String, Object> speaker = new LinkedHashMap<>();
            speaker.put("speaker", sp.label());
            speaker.put("name", sp.name());
            speaker.put("statements", statements);
            speakers.add(speaker);
        }
        msg.put("speakers", speakers);
        send(id, out, msg);
    }

    private void send(String id, WebSocketSession out, Map<String, Object> msg) {
        if (!out.isOpen()) return;
        try {
            out.sendMessage(new TextMessage(json.writeValueAsString(msg)));
        } catch (IOException | IllegalStateException e) {
            log.warn("[{}] could not send {}: {}", id, msg.get("type"), e.getMessage());
        }
    }
}
