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
import java.util.regex.Pattern;

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
    /** ...but never more audio than this: the carry is added to the next segment's length. */
    private static final int MAX_HOLD_BACK = 2 * SR;
    /**
     * Crosstalk can make the model stop early (live test: 5 s of audio came back as "Let me
     * interrupt just a moment." and the 3 s after it were missing). If the last word starts
     * further than this from the end of the segment, the rest is transcribed on its own.
     */
    private static final int MAX_UNHEARD_TAIL = 2 * SR;
    /** Audio kept in front of carried words, so the first one doesn't start abruptly. */
    private static final int CARRY_LEAD_IN = (int) (0.08 * SR);
    /**
     * A sentence that ends early in a segment used to wait for the segment to close (up to 5 s,
     * and 6 s measured on a live run). So the open segment is transcribed while it grows — first
     * at this length, then every {@link #PEEK_EVERY} — and closed as soon as a sentence has ended.
     */
    private static final int FIRST_PEEK = (int) (1.5 * SR);
    private static final int PEEK_EVERY = SR;
    /** A full stop on the last word heard is not to be trusted; one with this many words after it is. */
    private static final int WORDS_AFTER_SENTENCE_END = 2;
    private static final Pattern ENDS_SENTENCE = Pattern.compile(".*[.?!][\"']?");
    private static final Pattern TITLE = Pattern.compile("(?i)(?:mr|mrs|ms|dr|sen|gov|rep|gen|prof|st|vs)\\.");

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
    /** The carried words as they were heard before the cut, to check the second hearing against. */
    private List<Word> heldWords = List.of();
    /** The carry begins right after a sentence end, so its first word starts a sentence. */
    private boolean carryStartsSentence;
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
        int nextPeek = FIRST_PEEK;
        try {
            while (true) {
                float[] chunk = audio.take();
                if (chunk == END_OF_STREAM) {
                    for (SpeechSegmenter.Segment seg : segmenter.flush()) process(seg, null);
                    if (carry != null) {
                        process(new SpeechSegmenter.Segment(new float[0], carryStartSample, false), null);
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
                        List<SpeechSegmenter.Segment> closed = segmenter.accept(window);
                        for (SpeechSegmenter.Segment seg : closed) process(seg, null);
                        if (!closed.isEmpty() || segmenter.openSamples() == 0) {
                            nextPeek = FIRST_PEEK;
                        } else if (segmenter.openSamples() >= nextPeek) {
                            nextPeek = cutAtSentenceEnd() ? FIRST_PEEK : segmenter.openSamples() + PEEK_EVERY;
                        }
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.error("[{}] pipeline crashed", sessionId, e);
        }
    }

    /**
     * Transcribe the open segment; if a sentence has ended in it, close the segment now so the
     * sentence goes out at once. The words after the sentence end are carried like the words held
     * back at any forced cut. Returns whether the segment was closed.
     */
    private boolean cutAtSentenceEnd() {
        float[] samples = segmenter.openAudio();
        int fresh = 0;
        if (carry != null) {
            float[] joined = new float[carry.length + samples.length];
            System.arraycopy(carry, 0, joined, 0, carry.length);
            System.arraycopy(samples, 0, joined, carry.length, samples.length);
            samples = joined;
            if (carrySpeaker != null) fresh = carry.length;
        }
        List<Word> words = words(models.transcribe(samples));
        int last = lastSentenceEnd(words, samples.length);
        if (last < 0) return false;

        // Cutting here leaves a short part to be labelled. A voice not heard before needs 2.5 s
        // to become a speaker, so only cut early when the part clearly belongs to someone known
        // (or is all but covered by carried audio, whose speaker is known already).
        int cut = words.get(last + 1).startSample();
        int unknown = cut - fresh;
        if (unknown < MIN_FOR_NEW_SPEAKER && !(fresh > 0 && unknown < SR / 2)) {
            if (unknown < MIN_FOR_SPEAKER_ID) return false;
            SpeakerTracker.Match m = tracker.nearest(models.speakerEmbedding(Arrays.copyOfRange(samples, fresh, cut)));
            if (m == null || m.similarity() < tracker.threshold()) return false;
        }
        SpeechSegmenter.Segment segment = segmenter.cutNow();
        if (segment == null) return false;
        log.debug("[{}] sentence ended at '{}', closing the segment early", sessionId, words.get(last).text().trim());
        process(segment, words);
        return true;
    }

    /**
     * Index of the last word that ends a sentence, has at least two words after it (the next one
     * starting a new sentence) and is close enough to the end for the rest to be carried; else -1.
     */
    private static int lastSentenceEnd(List<Word> words, int length) {
        for (int i = words.size() - 1 - WORDS_AFTER_SENTENCE_END; i >= 0; i--) {
            if (length - words.get(i + 1).startSample() > MAX_HOLD_BACK) return -1;
            String word = words.get(i).text().trim();
            String next = words.get(i + 1).text().trim();
            if (ENDS_SENTENCE.matcher(word).matches() && !TITLE.matcher(word).matches()
                    && !next.isEmpty() && (Character.isUpperCase(next.charAt(0)) || Character.isDigit(next.charAt(0)))) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ per segment

    /** @param heard the segment's words if it was already transcribed (with any carry), else null */
    private void process(SpeechSegmenter.Segment segment, List<Word> heard) {
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
        List<Word> words = heard != null ? new ArrayList<>(heard) : words(models.transcribe(samples));
        if (heard == null && !words.isEmpty()
                && samples.length - words.get(words.size() - 1).startSample() > MAX_UNHEARD_TAIL) {
            int tailFrom = words.get(words.size() - 1).startSample();
            List<Word> tail = words(models.transcribe(Arrays.copyOfRange(samples, tailFrom, samples.length)));
            List<String> lastHeard = new ArrayList<>();
            for (int i = Math.max(0, words.size() - 3); i < words.size(); i++) lastHeard.add(norm(words.get(i).text()));
            int k = repeatedPrefix(lastHeard, tail);
            log.debug("[{}] transcript stopped {}s early, tail: {}", sessionId,
                    fmt((samples.length - tailFrom) / (double) SR), tail.stream().map(Word::text).toList());
            for (Word w : tail.subList(k, tail.size())) words.add(new Word(w.text(), w.startSample() + tailFrom));
        }
        long t2 = System.nanoTime();

        // Word timestamps run slightly late, so carried audio can include the tail of the
        // previous word and the model hears it again ("stealing stealing"). Drop the repeat.
        if (continuesCut) {
            int k = repeatedPrefix(wordsBeforeCut, words);
            log.debug("[{}] dedupe before={} held={} first={} k={}", sessionId, wordsBeforeCut,
                    heldWords.stream().limit(3).map(Word::text).toList(),
                    words.stream().limit(3).map(w -> "'" + w.text() + "'").toList(), k);
            if (k > 0) words.subList(0, k).clear();
            // Held words may only be put back if they can have been real: half a second of audio
            // at least, and no sign (k > 0) that the carry overlaps words already emitted. A cut
            // 0.2 s before the end once produced "I'm not going" out of nothing.
            boolean restorable = carriedSpeaker != null && k == 0 && carriedLen >= SR / 2;
            reconcileWithFirstHearing(words, heldWords, restorable ? carriedLen : 0);
            // The model hears the end of the previous sentence as "Energy, but we're..."; with
            // the repeat dropped, the new sentence would start in lower case.
            if (carryStartsSentence && !words.isEmpty()) {
                String w = words.get(0).text();
                int at = w.length() - w.stripLeading().length();
                if (at < w.length()) {
                    words.set(0, new Word(w.substring(0, at) + Character.toUpperCase(w.charAt(at)) + w.substring(at + 1),
                            words.get(0).startSample()));
                }
            }
        }
        wordsBeforeCut = List.of();
        heldWords = List.of();
        carryStartsSentence = false;

        // A forced cut probably sliced the last word in half, and the model may also mishear the
        // words just before it ("into a company" → "in 2015"). Hold back the last few words and
        // carry their audio into the next segment, where they're heard with full context.
        int end = samples.length;
        boolean heldBack = false;
        List<Word> cutWords = List.of();
        // A segment closed because a sentence ended is cut right after that sentence instead.
        int sentenceEnd = heard != null ? lastSentenceEnd(words, samples.length) : -1;
        int hold = sentenceEnd >= 0 ? words.size() - 1 - sentenceEnd : HOLD_BACK_WORDS;
        if (segment.forcedCut() && words.size() > hold
                && samples.length - words.get(words.size() - hold).startSample() <= MAX_HOLD_BACK) {
            heldBack = true;
            Word first = words.get(words.size() - hold);
            cutWords = List.copyOf(words.subList(words.size() - hold, words.size()));
            log.debug("[{}] cut: holding back {}", sessionId, cutWords.stream().map(Word::text).toList());
            words.subList(words.size() - hold, words.size()).clear();
            // Fast speech puts word starts closer together than the lead-in; the cut must stay
            // after the last word kept, or that word falls outside every part and is lost.
            end = Math.max(words.get(words.size() - 1).startSample() + 1, first.startSample() - CARRY_LEAD_IN);
            heldWords = cutWords;
            carryStartsSentence = sentenceEnd >= 0;
            carry = Arrays.copyOfRange(samples, end, samples.length);
            carryStartSample = startSample + end;
            List<String> tail = new ArrayList<>();
            for (int i = Math.max(0, words.size() - 3); i < words.size(); i++) tail.add(norm(words.get(i).text()));
            wordsBeforeCut = tail;
        }

        // Move each speaker change to the nearest pause between words, then cut into parts.
        List<Part> parts = new ArrayList<>();
        int from = 0;
        boolean carriedPart = fresh > 0;
        if (carriedPart) {
            from = Math.min(fresh, end);
            parts.add(new Part(0, from));
        }
        // The held-back words count as gaps too: a change found near the cut usually is the cut
        // ("...the best ever at it. | Let me"), and must not be moved to the gap before it.
        List<Word> allWords = new ArrayList<>(words);
        allWords.addAll(cutWords);
        for (int b : boundaries) {
            int snapped = snapToWordGap(b, allWords);
            if (snapped - from >= SR / 2 && snapped < end) {
                parts.add(new Part(from, snapped));
                from = snapped;
            }
        }
        if (from < end || parts.isEmpty()) parts.add(new Part(from, end));

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
            } else if (i > 0 && i < parts.size() - 1 && part.length() >= MIN_FOR_SPEAKER_ID / 2) {
                // Too short to fingerprint alone, but split off on both sides ("Yes."): the voice
                // differs from its neighbours, so use the 1 s window around it rather than
                // handing it to a neighbour.
                int mid = (part.from + part.to) / 2;
                int wFrom = Math.max(0, Math.min(mid - WINDOW / 2, samples.length - WINDOW));
                embeddings[i] = models.speakerEmbedding(Arrays.copyOfRange(samples, wFrom, Math.min(samples.length, wFrom + WINDOW)));
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
                List<String> tail = new ArrayList<>();
                List<Word> again = new ArrayList<>();
                int carryFrom = Math.max(0, held.from - CARRY_LEAD_IN);
                for (Word w : words) {
                    if (w.startSample() < held.from) {
                        tail.add(norm(w.text()));
                        carryFrom = Math.max(carryFrom, w.startSample() + 1);
                    } else {
                        again.add(w);
                    }
                }
                again.addAll(cutWords);
                heldWords = again;
                carryStartsSentence = false;
                carry = Arrays.copyOfRange(samples, carryFrom, samples.length);
                carryStartSample = startSample + carryFrom;
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

        // Words cut off mid-sentence belong to whoever was speaking. Words after a sentence end
        // may be the next speaker's first ("...ever had. | That is"), so those are identified again.
        carrySpeaker = heldBack && carry != null && sentenceEnd < 0 ? labels[parts.size() - 1] : null;

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

    /**
     * Carried words are heard twice: before the cut (with the sentence so far as context, but
     * maybe sliced at the end) and again at the start of the next segment (whole, but starting
     * abruptly). The second hearing wins, except for what the abrupt start does to it:
     *  - the first word goes missing ("how do you bring" → "Do you bring");
     *  - the end of the word before the cut becomes a word of its own ("she started" → "And she started");
     *  - under crosstalk nothing of the carried words comes back at all;
     *  - the first word is capitalised as if it began a sentence.
     * {@code carriedLen} is the length of carried audio whose words may be restored if none of
     * them come back (0 = never restore).
     */
    private static void reconcileWithFirstHearing(List<Word> heard, List<Word> held, int carriedLen) {
        if (held.isEmpty()) return;
        if (carriedLen > 0 && (heard.isEmpty() || heard.get(0).startSample() >= carriedLen)
                && !sameWord(heard, 0, held, 0) && !sameWord(heard, 0, held, 1)) {
            for (int i = 0; i < held.size(); i++) {
                heard.add(i, new Word(held.get(i).text(), (int) ((long) carriedLen * i / held.size())));
            }
            return;
        }
        if (!sameWord(heard, 0, held, 0)) {
            if (sameWord(heard, 0, held, 1) && sameWord(heard, 1, held, 2)) {
                heard.set(0, new Word(withCaseOf(held.get(1).text(), heard.get(0).text()), heard.get(0).startSample()));
                heard.add(0, new Word(held.get(0).text(), 0));
                return;
            }
            if (sameWord(heard, 1, held, 0) && sameWord(heard, 2, held, 1)) heard.remove(0);
        }
        if (sameWord(heard, 0, held, 0)) {
            heard.set(0, new Word(withCaseOf(held.get(0).text(), heard.get(0).text()), heard.get(0).startSample()));
        }
    }

    private static boolean sameWord(List<Word> a, int i, List<Word> b, int j) {
        return i < a.size() && j < b.size() && norm(a.get(i).text()).equals(norm(b.get(j).text()));
    }

    /** {@code word}, starting in lower case if {@code model} does. */
    private static String withCaseOf(String model, String word) {
        String m = model.stripLeading();
        int at = word.length() - word.stripLeading().length();
        if (m.isEmpty() || at >= word.length() || !Character.isLowerCase(m.charAt(0))) return word;
        return word.substring(0, at) + Character.toLowerCase(word.charAt(at)) + word.substring(at + 1);
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
        if (bestBoundary < 0 || bestSim >= SPLIT_THRESHOLD) {
            splitOffInterjection(all, ws, from, to, out);
            return;
        }
        out.add(bestBoundary);
        split(all, from, bestBoundary, out);
        split(all, bestBoundary, to, out);
    }

    /**
     * A short turn inside someone else's speech ("That is not true. | Secretary, your response. |
     * Look, we need...") has the same voice on both sides, so no single boundary separates two
     * different voices. Look for the stretch that differs most from everything around it instead.
     */
    private void splitOffInterjection(List<Window> all, List<Window> ws, int from, int to, List<Integer> out) {
        int bestStart = -1;
        int bestEnd = -1;
        float bestSim = Float.MAX_VALUE;
        for (int a = from + MIN_PART; a <= to - 2 * MIN_PART; a += HOP) {
            for (int b = a + MIN_PART; b <= to - MIN_PART; b += HOP) {
                final int start = a;
                final int stop = b;
                float[] inside = mean(ws.stream().filter(w -> w.from >= start && w.to <= stop).toList());
                float[] around = mean(ws.stream().filter(w -> w.to <= start || w.from >= stop).toList());
                if (inside == null || around == null) continue;
                float sim = dot(inside, around);
                if (sim < bestSim) {
                    bestSim = sim;
                    bestStart = a;
                    bestEnd = b;
                }
            }
        }
        if (bestStart < 0 || bestSim >= SPLIT_THRESHOLD) return;
        out.add(bestStart);
        out.add(bestEnd);
        split(all, from, bestStart, out);
        split(all, bestStart, bestEnd, out);
        split(all, bestEnd, to, out);
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
