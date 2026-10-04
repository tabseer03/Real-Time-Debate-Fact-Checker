package com.debatechecker.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * One virtual thread per speaker.
 *
 * Speech segments end at pauses, not at sentence boundaries, so one segment may hold half a
 * sentence or three of them. This worker stitches a speaker's segments together and emits
 * complete sentences. If the speaker goes quiet for a while, whatever is left is emitted too.
 *
 * It also keeps the speaker's last few sentences, which milestone 3 will use to resolve
 * "he", "that bill", "those numbers" when rewriting a sentence into a standalone claim.
 */
public final class SpeakerWorker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpeakerWorker.class);

    /** Sentence boundary: . ! or ? then whitespace then something that starts a new sentence. */
    private static final Pattern SENTENCE_BREAK = Pattern.compile("(?<=[.!?])\\s+(?=[A-Z0-9\"'])");
    /**
     * Emit an unfinished sentence once its speaker has been quiet this long — but only if their
     * last segment ended at a real pause. After a length-cap cut, the rest of the sentence is
     * still being processed, so we wait for it instead.
     */
    private static final long FLUSH_AFTER_MS = 2000;
    private static final int CONTEXT_SIZE = 5;
    /**
     * Run-on speech ("..., and ..., so ...") may not hit a full stop for 20 seconds. Past this
     * many words, release it at the last comma so claims still arrive within the latency budget.
     */
    private static final int MAX_PENDING_WORDS = 30;
    private static final int MIN_CLAUSE_WORDS = 8;

    /**
     * A transcribed speech segment on its way to a speaker's worker.
     * @param endWallNanos   System.nanoTime() at which the end of this audio was heard (for latency)
     * @param endsAtPause    false if the speaker was cut off by the segment-length cap mid-flow
     */
    public record Segment(String text, double audioStartSec, double audioEndSec, long endWallNanos,
                          boolean endsAtPause) {}

    private static final Segment POISON = new Segment("", 0, 0, 0, true);
    private static final Segment SOMEONE_ELSE_SPOKE = new Segment("", 0, 0, 0, true);
    /** Even after a cut, give up waiting for the rest of a sentence after this long. */
    private static final long MAX_WAIT_AFTER_CUT_MS = 8000;

    private final String speaker;
    private final Consumer<Sentence> sink;
    private final BlockingQueue<Segment> inbox = new LinkedBlockingQueue<>();
    private final Deque<String> context = new ArrayDeque<>();
    private final Thread thread;

    private final StringBuilder pending = new StringBuilder();
    private double pendingStart = -1;
    private double pendingEnd;
    private long pendingEndWall;
    private double lastSegmentStart;
    private long lastInputNanos = System.nanoTime();
    private boolean lastEndedAtPause = true;

    public SpeakerWorker(String speaker, Consumer<Sentence> sink) {
        this.speaker = speaker;
        this.sink = sink;
        this.thread = Thread.ofVirtual().name("speaker-" + speaker).start(this::run);
    }

    public void submit(Segment segment) {
        inbox.add(segment);
    }

    /** Another speaker took the floor: whatever this speaker left unfinished is finished. */
    public void otherSpeakerStarted() {
        inbox.add(SOMEONE_ELSE_SPOKE);
    }

    /** The speaker's most recent sentences, oldest first. */
    public synchronized List<String> recentContext() {
        return List.copyOf(context);
    }

    private void run() {
        try {
            while (true) {
                Segment s = inbox.poll(FLUSH_AFTER_MS, TimeUnit.MILLISECONDS);
                if (s == null) {          // speaker has been quiet: emit the unfinished sentence
                    long quietMs = (System.nanoTime() - lastInputNanos) / 1_000_000;
                    if (lastEndedAtPause || quietMs >= MAX_WAIT_AFTER_CUT_MS) flushAll();
                    continue;
                }
                if (s == SOMEONE_ELSE_SPOKE) {
                    flushAll();
                    continue;
                }
                if (s == POISON) {
                    flushAll();
                    return;
                }
                append(s);
                emitCompleteSentences();
                emitClauseIfTooLong();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("speaker {} worker crashed", speaker, e);
        }
    }

    private void append(Segment s) {
        if (pending.isEmpty()) {
            pendingStart = s.audioStartSec();
        } else {
            pending.append(' ');
        }
        pending.append(s.text());
        pendingEnd = s.audioEndSec();
        lastSegmentStart = s.audioStartSec();
        lastInputNanos = System.nanoTime();
        pendingEndWall = s.endWallNanos();
        lastEndedAtPause = s.endsAtPause();
    }

    private void emitCompleteSentences() {
        String text = pending.toString();
        String[] parts = SENTENCE_BREAK.split(text);
        boolean lastIsComplete = text.matches("(?s).*[.!?][\"']?$");
        int completeCount = lastIsComplete ? parts.length : parts.length - 1;
        if (completeCount <= 0) return;

        // Timestamps are per segment, so every sentence from this batch shares the batch's span.
        for (int i = 0; i < completeCount; i++) {
            emit(parts[i]);
        }
        pending.setLength(0);
        if (!lastIsComplete) {
            pending.append(parts[parts.length - 1]);
            pendingStart = lastSegmentStart; // the leftover began in the latest segment
        } else {
            pendingStart = -1;
        }
    }

    private void emitClauseIfTooLong() {
        String text = pending.toString();
        if (text.isBlank() || text.trim().split("\\s+").length <= MAX_PENDING_WORDS) return;
        int cut = text.lastIndexOf(", ");
        if (cut < 0 || text.substring(0, cut).trim().split("\\s+").length < MIN_CLAUSE_WORDS) return;
        emit(text.substring(0, cut + 1));
        pending.setLength(0);
        pending.append(text.substring(cut + 2));
        pendingStart = lastSegmentStart;
    }

    private void flushAll() {
        if (!pending.isEmpty()) {
            for (String part : SENTENCE_BREAK.split(pending.toString())) {
                emit(part);
            }
            pending.setLength(0);
            pendingStart = -1;
        }
    }

    private void emit(String sentence) {
        String trimmed = sentence.trim();
        if (trimmed.isEmpty()) return;
        long latencyMs = (System.nanoTime() - pendingEndWall) / 1_000_000;
        synchronized (this) {
            context.addLast(trimmed);
            while (context.size() > CONTEXT_SIZE) context.removeFirst();
        }
        sink.accept(new Sentence(speaker, trimmed, Math.max(pendingStart, 0), pendingEnd, latencyMs));
    }

    @Override
    public void close() {
        inbox.add(POISON);
        try {
            thread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
