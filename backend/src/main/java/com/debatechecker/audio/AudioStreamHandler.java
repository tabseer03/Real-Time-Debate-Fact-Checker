package com.debatechecker.audio;

import com.debatechecker.speech.Sentence;
import com.debatechecker.speech.SessionPipeline;
import com.debatechecker.speech.SpeechModels;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Receives 16 kHz mono Int16 PCM from the extension, runs it through the speech pipeline,
 * and sends each finished sentence back to the extension as JSON.
 */
@Component
public class AudioStreamHandler extends BinaryWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AudioStreamHandler.class);

    private record Connection(AudioSession recording, SessionPipeline pipeline, WebSocketSession out) {}

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final SpeechModels models;
    private final ObjectMapper json = new ObjectMapper();
    private final String recordingsDir;
    private final float speakerThreshold;
    private final int maxSpeakers;

    public AudioStreamHandler(SpeechModels models,
                              @Value("${debatechecker.recordings-dir}") String recordingsDir,
                              @Value("${debatechecker.speaker-threshold}") float speakerThreshold,
                              @Value("${debatechecker.max-speakers}") int maxSpeakers) {
        this.models = models;
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
        SessionPipeline pipeline = new SessionPipeline(id, models, speakerThreshold, maxSpeakers,
                sentence -> send(id, out, sentence));
        connections.put(ws.getId(), new Connection(new AudioSession(id, recordingsDir), pipeline, out));
        log.info("[{}] connected", id);
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        log.info("[{}] control: {}", ws.getId().substring(0, 8), message.getPayload());
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
        }
        log.info("[{}] closed: {}", ws.getId().substring(0, 8), status);
    }

    @Override
    public void handleTransportError(WebSocketSession ws, Throwable ex) {
        log.warn("[{}] transport error: {}", ws.getId().substring(0, 8), ex.getMessage());
    }

    private void send(String id, WebSocketSession out, Sentence s) {
        log.info("[{}] SENTENCE [{}] ({} ms) {}", id, s.speaker(), s.latencyMs(), s.text());
        if (!out.isOpen()) return;
        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "sentence");
            msg.put("speaker", s.speaker());
            msg.put("text", s.text());
            msg.put("start", s.audioStartSec());
            msg.put("end", s.audioEndSec());
            msg.put("latencyMs", s.latencyMs());
            out.sendMessage(new TextMessage(json.writeValueAsString(msg)));
        } catch (IOException | IllegalStateException e) {
            log.warn("[{}] could not send sentence: {}", id, e.getMessage());
        }
    }
}
