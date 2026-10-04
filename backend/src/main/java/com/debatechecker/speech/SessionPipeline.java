package com.debatechecker.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * Speech processing for one capture session (one tab):
 *
 *   PCM → SpeechSegmenter (cuts at pauses, hard cap on length)
 *       → speaker fingerprints on 1 s windows every 0.25 s
 *       → split the segment wherever the voice changes (handoffs often have no pause)
 *       → speech-to-text once per segment; words go to each part by their timestamps
 *       → SpeakerTracker labels each part S0 / S1 / ...
 *       → that speaker's SpeakerWorker (one virtual thread per speaker) → sentences
 *
 * The WebSocket thread only enqueues audio; all model work runs on this session's thread.
 */
public final class SessionPipeline implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SessionPipeline.class);

    private static final int SR = SpeechModels.SAMPLE_RATE;
    private static final int WINDOW = SR;                       // speaker-fingerprint window (1 s)
    private static final int HOP = SR / 4;                      // one fingerprint every 0.25 s
    private static final int MIN_PART = SR;                     // a speaker turn shorter than this can't be split off
    private static final int MIN_FOR_SPEAKER_ID = (int) (0.8 * SR);
    /**
     * Fingerprints of 1-2 s of broadcast audio are too noisy to introduce a speaker: in live tests
     * "Well", "Yes." and a 1.7 s moderator line each became bogus new speakers. Only parts at least
     * this long may create a speaker or update a speaker's stored fingerprint.
     */
    private static final int MIN_FOR_NEW_SPEAKER = (int) (2.5 * SR);
    /**
     * ...unless it is at least 1.5 s long and clearly unlike everyone known (similarity below
     * this): then it's a new voice, e.g. a moderator whose turns are all brief. Fragments of known
     * voices scored 0.25-0.43 in live tests; genuinely different voices 0.1-0.2.
     */
    private static final int MIN_FOR_NEW_IF_UNLIKE_ALL = (int) (1.5 * SR);
    private static final float UNLIKE_ALL_SIMILARITY = 0.2f;
    /** A short part joins its most similar known speaker if at least this similar... */
    private static final float SHORT_PART_MIN_SIMILARITY = 0.2f;
    /** Left and right of a candidate split less similar than this → different speakers. */
    private static final float SPLIT_THRESHOLD = 0.45f;
    private static final float[] END_OF_STREAM = new float[0];
    /** Words re-transcribed in the next segment after a forced cut. */
    private static final int HOLD_BACK_WORDS = 3;

    private final String sessionId;
    private final SpeechModels models;
    private final SpeakerTracker tracker;
    private final Consumer<Sentence> sink;
    private final SpeechSegmenter segmenter;
    private final BlockingQueue<float[]> audio = new LinkedBlockingQueue<>();
    private final Map<String, SpeakerWorker> workers = new ConcurrentHashMap<>();
    private final Thread thread;

    /** Wall-clock time of audio position 0, set when the first chunk arrives (for latency). */
    private volatile long audioZeroNanos = -1;
    private String lastSpeaker;
    /** Audio of a word cut off by a forced segment cut; prepended to the next segment. */
    private float[] carry;
    private long carryStartSample;
    /** Last few words kept before a forced cut, to drop them if the next segment repeats them. */
    private List<String> wordsBeforeCut = List.of();
    /** Speaker of the carried words, if they were already identified (null = identify them again). */
    private String carrySpeaker;

    public SessionPipeline(String sessionId, SpeechModels models, float speakerThreshold, int maxSpeakers,
                           Consumer<Sentence> sink) {
        this.sessionId = sessionId;
        this.models = models;
        this.tracker = new SpeakerTracker(speakerThreshold, maxSpeakers);
        this.sink = sink;
        SpeechModels.Settings s = models.settings();
        this.segmenter = new SpeechSegmenter(models.newVad(), s.vadMinSilenceSeconds(), s.vadMaxSegmentSeconds());
        this.thread = Thread.ofVirtual().name("pipeline-" + sessionId).start(this::run);
    }

    /** Called from the WebSocket thread with little-endian Int16 mono PCM. Never blocks. */
    public void feed(ByteBuffer pcm) {
        ByteBuffer b = pcm.slice().order(ByteOrder.LITTLE_ENDIAN);
        float[] samples = new float[b.remaining() / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = b.getShort() / 32768f;
        }
        if (audioZeroNanos < 0) {
            // the first chunk is already samples.length long when it arrives
            audioZeroNanos = System.nanoTime() - (long) (samples.length * 1e9 / SR);
        }
        audio.add(samples);
    }

    private void run() {
        float[] window = new float[SpeechModels.VAD_WINDOW];
        int filled = 0;
        try {
            while (true) {
                float[] chunk = audio.take();
                if (chunk == END_OF_STREAM) {
                    for (SpeechSegmenter.Segment seg : segmenter.flush()) process(seg);
                    if (carry != null) {
                        process(new SpeechSegmenter.Segment(new float[0], carryStartSample, false));
                    }
                    return;
                }
                int pos = 0;
                while (pos < chunk.length) {
                    int n = Math.min(window.length - filled, chunk.length - pos);
                    System.arraycopy(chunk, pos, window, filled, n);
                    filled += n;
                    pos += n;
                    if (filled == window.length) {
                        filled = 0;
                        for (SpeechSegmenter.Segment seg : segmenter.accept(window)) process(seg);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("[{}] pipeline crashed", sessionId, e);
        }
    }

    // ------------------------------------------------------------------ per segment

    private void process(SpeechSegmenter.Segment segment) {
        float[] samples = segment.samples();
        long startSample = segment.startSample();
        boolean continuesCut = carry != null;
        String carriedSpeaker = continuesCut ? carrySpeaker : null;
        int carriedLen = 0;
        if (carry != null) {                       // finish the word cut off last time
            carriedLen = carry.length;
            float[] joined = new float[carry.length + samples.length];
            System.arraycopy(carry, 0, joined, 0, carry.length);
            System.arraycopy(samples, 0, joined, carry.length, samples.length);
            samples = joined;
            startSample = carryStartSample;
            carry = null;
        }
        double segStart = startSample / (double) SR;

        long t0 = System.nanoTime();
        // Words carried over with a known speaker keep that label; look for speaker changes only
        // in the new audio (the carried second of the previous speaker would confuse it).
        int fresh = carriedSpeaker != null ? carriedLen : 0;
        List<Integer> boundaries = new ArrayList<>();
        for (int b : speakerChangePoints(Arrays.copyOfRange(samples, fresh, samples.length))) {
            boundaries.add(b + fresh);
        }
        long t1 = System.nanoTime();
        List<Word> words = words(models.transcribe(samples));
        long t2 = System.nanoTime();

        // Word timestamps run slightly late, so carried audio can include the tail of the
        // previous word and the model hears it again ("stealing stealing"). Drop the repeat.
        if (continuesCut) {
            int k = repeatedPrefix(wordsBeforeCut, words);
            log.debug("[{}] dedupe before={} first={} k={}", sessionId, wordsBeforeCut,
                    words.stream().limit(3).map(w -> "'" + w.text() + "'").toList(), k);
            if (k > 0) words.subList(0, k).clear();
        }
        wordsBeforeCut = List.of();

        // A forced cut probably sliced the last word in half, and the model may also mishear the
        // words just before it ("into a company" → "in 2015"). Hold back the last few words and
        // carry their audio into the next segment, where they're heard with full context.
        int end = samples.length;
        boolean heldBack = false;
        if (segment.forcedCut() && words.size() > HOLD_BACK_WORDS) {
            heldBack = true;
            Word first = words.get(words.size() - HOLD_BACK_WORDS);
            log.debug("[{}] cut: holding back {}", sessionId,
                    words.subList(words.size() - HOLD_BACK_WORDS, words.size()).stream().map(Word::text).toList());
            words.subList(words.size() - HOLD_BACK_WORDS, words.size()).clear();
            end = Math.max(0, first.startSample() - (int) (0.08 * SR));
            carry = Arrays.copyOfRange(samples, end, samples.length);
            carryStartSample = startSample + end;
            List<String> tail = new ArrayList<>();
            for (int i = Math.max(0, words.size() - 3); i < words.size(); i++) tail.add(norm(words.get(i).text()));
            wordsBeforeCut = tail;
        }

        // Move each speaker change to the nearest pause between words, then cut into parts.
        List<Part> parts = new ArrayList<>();
        int from = 0;
        boolean carriedPart = fresh > 0 && fresh < end;
        if (carriedPart) {
            parts.add(new Part(0, fresh));
            from = fresh;
        }
        for (int b : boundaries) {
            int snapped = snapToWordGap(b, words);
            if (snapped - from >= SR / 2 && snapped < end) {
                parts.add(new Part(from, snapped));
                from = snapped;
            }
        }
        parts.add(new Part(from, end));

        // Label long parts first (they are reliable and may introduce speakers), then short ones.
        String[] labels = new String[parts.size()];
        String[] notes = new String[parts.size()];
        float[][] embeddings = new float[parts.size()][];
        if (carriedPart) {
            labels[0] = carriedSpeaker;
            notes[0] = "carried";
        }
        for (int i = carriedPart ? 1 : 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            if (part.length() >= MIN_FOR_SPEAKER_ID) {
                embeddings[i] = models.speakerEmbedding(Arrays.copyOfRange(samples, part.from, part.to));
            }
            boolean firstEver = tracker.speakerCount() == 0 && embeddings[i] != null;
            if (part.length() >= MIN_FOR_NEW_SPEAKER || firstEver) {
                SpeakerTracker.Match m = tracker.assign(embeddings[i]);
                labels[i] = m.label();
                notes[i] = String.format("%.2f%s", m.similarity(), m.isNewSpeaker() ? " NEW" : "");
            }
        }
        for (int i = 0; i < parts.size(); i++) {
            if (labels[i] != null) continue;
            SpeakerTracker.Match m = embeddings[i] == null ? null : tracker.nearest(embeddings[i]);
            boolean confident = m != null && m.similarity() >= tracker.threshold();
            boolean lastOfCut = i == parts.size() - 1 && i > 0 && segment.forcedCut();
            if (!confident && lastOfCut) {
                // Probably the first words of a new turn that continues past the cut. Hold them
                // back and prepend them to the next segment, where the turn is long enough to
                // identify (otherwise they'd be pinned on the previous speaker).
                Part held = parts.remove(i);
                heldBack = false;                  // carried audio must be identified again
                carry = Arrays.copyOfRange(samples, held.from, samples.length);
                carryStartSample = startSample + held.from;
                List<String> tail = new ArrayList<>();
                for (Word w : words) if (w.startSample() < held.from) tail.add(norm(w.text()));
                wordsBeforeCut = tail.subList(Math.max(0, tail.size() - 3), tail.size());
                end = held.from;
                break;
            }
            if (embeddings[i] != null && parts.get(i).length() >= MIN_FOR_NEW_IF_UNLIKE_ALL
                    && (m == null || m.similarity() < UNLIKE_ALL_SIMILARITY)) {
                SpeakerTracker.Match created = tracker.assign(embeddings[i]);
                labels[i] = created.label();
                notes[i] = String.format("%.2f%s, unlike all", created.similarity(), created.isNewSpeaker() ? " NEW" : "");
            } else if (m != null && m.similarity() >= SHORT_PART_MIN_SIMILARITY) {
                labels[i] = m.label();
                notes[i] = String.format("short, %.2f", m.similarity());
            } else {
                // ...otherwise it goes with its neighbour.
                labels[i] = i > 0 && labels[i - 1] != null ? labels[i - 1]
                        : i + 1 < parts.size() && labels[i + 1] != null ? labels[i + 1]
                        : lastSpeaker;
                notes[i] = "short, neighbour";
                if (labels[i] == null) {                 // very first audio of the session
                    SpeakerTracker.Match first = tracker.assign(embeddings[i] != null ? embeddings[i]
                            : models.speakerEmbedding(Arrays.copyOfRange(samples, parts.get(i).from, parts.get(i).to)));
                    labels[i] = first.label();
                }
            }
        }

        carrySpeaker = heldBack && carry != null ? labels[parts.size() - 1] : null;

        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            Part part = parts.get(i);
            String text = textBetween(words, part.from, part.to);
            String speaker = labels[i];
            String note = notes[i];
            double start = segStart + part.from / (double) SR;
            double stop = segStart + part.to / (double) SR;
            summary.append(String.format("%n      %s %.1f-%.1fs (%s): %s", speaker, start, stop, note,
                    text.isEmpty() ? "<no words>" : text));

            if (text.isEmpty()) continue;
            if (!speaker.equals(lastSpeaker)) {
                final String current = speaker;
                workers.forEach((label, w) -> { if (!label.equals(current)) w.otherSpeakerStarted(); });
            }
            lastSpeaker = speaker;
            long endWall = audioZeroNanos + (long) (stop * 1e9);
            workers.computeIfAbsent(speaker, s -> new SpeakerWorker(s, sink))
                   .submit(new SpeakerWorker.Segment(text, start, stop, endWall,
                           part.to < end || !segment.forcedCut()));
        }
        log.info("[{}] segment {}-{}s{}: {} part(s), speaker {}ms, asr {}ms{}", sessionId,
                fmt(segStart), fmt(segStart + end / (double) SR), segment.forcedCut() ? " (cut)" : "",
                parts.size(), (t1 - t0) / 1_000_000, (t2 - t1) / 1_000_000, summary);
    }

    /** One recognized word: its text (with leading space) and where it starts in the segment. */
    private record Word(String text, int startSample) {}

    private static List<Word> words(SpeechModels.Transcript t) {
        List<Word> out = new ArrayList<>();
        if (t.tokens().length == 0 || t.tokens().length != t.times().length) {
            if (!t.text().isEmpty()) out.add(new Word(t.text(), 0));
            return out;
        }
        StringBuilder cur = null;
        int curStart = 0;
        for (int i = 0; i < t.tokens().length; i++) {
            String tok = t.tokens()[i];
            if (cur == null || tok.startsWith(" ")) {
                if (cur != null) out.add(new Word(cur.toString(), curStart));
                cur = new StringBuilder();
                curStart = (int) (t.times()[i] * SR);
            }
            cur.append(tok);
        }
        if (cur != null) out.add(new Word(cur.toString(), curStart));
        return out;
    }

    /** How many of the first words in {@code words} repeat the end of {@code before} (max 3). */
    private static int repeatedPrefix(List<String> before, List<Word> words) {
        for (int k = Math.min(3, Math.min(before.size(), words.size())); k > 0; k--) {
            boolean same = true;
            for (int i = 0; i < k && same; i++) {
                same = before.get(before.size() - k + i).equals(norm(words.get(i).text()));
            }
            if (same) return k;
        }
        return 0;
    }

    private static String norm(String word) {
        return word.toLowerCase().replaceAll("[^a-z0-9%$']", "");
    }

    /** The word start nearest to {@code b} (within 0.75 s) that follows the biggest gap. */
    private static int snapToWordGap(int b, List<Word> words) {
        int best = b;
        int bestGap = -1;
        for (int i = 1; i < words.size(); i++) {
            int start = words.get(i).startSample();
            if (Math.abs(start - b) > 0.75 * SR) continue;
            int gap = start - words.get(i - 1).startSample();
            // A turn usually starts right after a sentence ends: "...bring the jobs back? | Well,"
            if (words.get(i - 1).text().matches(".*[.?!]$")) gap += (int) (0.4 * SR);
            if (gap > bestGap) {
                bestGap = gap;
                best = start;
            }
        }
        return best;
    }

    private static String textBetween(List<Word> words, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (Word w : words) {
            if (w.startSample() >= from && w.startSample() < to) sb.append(w.text());
        }
        return tidy(sb.toString());
    }

    /** A stretch of the segment, in samples, believed to be one speaker. */
    private record Part(int from, int to) {
        int length() { return to - from; }
    }

    /** One fingerprint per 1 s window, every 0.25 s. */
    private record Window(int from, int to, float[] embedding) {}

    /** Sample positions where the voice changes, found from 1 s fingerprints every 0.25 s. */
    private List<Integer> speakerChangePoints(float[] samples) {
        List<Integer> points = new ArrayList<>();
        if (samples.length < 2 * MIN_PART) return points;     // too short to hold two turns
        List<Window> windows = new ArrayList<>();
        for (int from = 0; from + WINDOW <= samples.length; from += HOP) {
            windows.add(new Window(from, from + WINDOW,
                    normalized(models.speakerEmbedding(Arrays.copyOfRange(samples, from, from + WINDOW)))));
        }
        split(windows, 0, samples.length, points);
        points.sort(null);
        return points;
    }

    /**
     * Find the point in [from, to) where the voice before and after differs most. If it differs
     * enough, record it and look again inside each side.
     */
    private void split(List<Window> all, int from, int to, List<Integer> out) {
        List<Window> ws = all.stream().filter(w -> w.from >= from && w.to <= to).toList();
        int bestBoundary = -1;
        float bestSim = Float.MAX_VALUE;
        for (int b = from + MIN_PART; b <= to - MIN_PART; b += HOP) {
            final int boundary = b;
            float[] left = mean(ws.stream().filter(w -> w.to <= boundary).toList());
            float[] right = mean(ws.stream().filter(w -> w.from >= boundary).toList());
            if (left == null || right == null) continue;
            float sim = dot(left, right);
            if (sim < bestSim) {
                bestSim = sim;
                bestBoundary = b;
            }
        }
        if (bestBoundary < 0 || bestSim >= SPLIT_THRESHOLD) return;
        out.add(bestBoundary);
        split(all, from, bestBoundary, out);
        split(all, bestBoundary, to, out);
    }

    /** Fix ASR spacing quirks like "with$14 million". */
    static String tidy(String s) {
        return s.trim().replaceAll("(\\w)\\$(\\d)", "$1 \\$$2").replaceAll("\\s+", " ");
    }

    // ------------------------------------------------------------------ helpers

    private static float[] mean(List<Window> ws) {
        if (ws.isEmpty()) return null;
        float[] m = new float[ws.get(0).embedding.length];
        for (Window w : ws) for (int i = 0; i < m.length; i++) m[i] += w.embedding[i];
        return normalized(m);
    }

    private static float dot(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static float[] normalized(float[] v) {
        double n = 0;
        for (float x : v) n += (double) x * x;
        n = Math.sqrt(n);
        float[] out = new float[v.length];
        if (n == 0) return out;
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / n);
        return out;
    }

    private static String fmt(double seconds) {
        return String.format("%.1f", seconds);
    }

    @Override
    public void close() {
        audio.add(END_OF_STREAM);
        try {
            thread.join(); // finish the backlog before releasing native objects
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        workers.values().forEach(SpeakerWorker::close);
        segmenter.close();
        log.info("[{}] pipeline closed, {} speaker(s) seen", sessionId, tracker.speakerCount());
    }
}
