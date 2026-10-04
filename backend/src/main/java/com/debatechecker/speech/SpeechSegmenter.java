package com.debatechecker.speech;

import com.k2fsa.sherpa.onnx.Vad;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Cuts the audio stream into speech segments.
 *
 * Uses Silero only for its per-window speech probability and applies our own rules, because
 * sherpa-onnx's built-in max_speech_duration is a soft limit (it just raises the threshold),
 * which let segments grow to 20+ seconds and blew the latency budget.
 *
 * Rules:
 *  - speech starts when probability >= 0.5, and continues while it stays >= 0.35;
 *  - a segment ends after {@code minSilenceSec} below 0.35;
 *  - a segment never exceeds {@code maxSegmentSec}: it is cut at the quietest window in its
 *    last 1.5 s (usually a gap between words) and the rest carries into the next segment.
 *
 * Not thread-safe; owned by one SessionPipeline thread.
 */
final class SpeechSegmenter implements AutoCloseable {

    /** @param forcedCut true if the segment was cut at the length cap (likely mid-word), not at a pause */
    record Segment(float[] samples, long startSample, boolean forcedCut) {}

    private static final int WIN = SpeechModels.VAD_WINDOW;          // 512 samples = 32 ms
    private static final float START_THRESHOLD = 0.5f;
    private static final float CONTINUE_THRESHOLD = 0.35f;
    private static final int PREROLL_WINDOWS = 3;                    // keep ~100 ms before speech onset
    private static final int TRAILING_PAD_WINDOWS = 2;               // and ~64 ms after it ends
    private static final int MIN_SPEECH_WINDOWS = 8;                 // drop blips under ~250 ms
    private static final int CUT_SEARCH_WINDOWS = (int) (1.5 * SpeechModels.SAMPLE_RATE / WIN);

    private final Vad vad;
    private final int minSilenceWindows;
    private final int maxSegmentWindows;

    private final Deque<float[]> preroll = new ArrayDeque<>();
    private final List<float[]> windows = new ArrayList<>();
    private final List<Float> probs = new ArrayList<>();
    private long windowIndex = 0;        // windows processed so far
    private long segmentStartWindow;
    private boolean inSpeech = false;
    private int silenceRun = 0;

    SpeechSegmenter(Vad vad, float minSilenceSec, float maxSegmentSec) {
        this.vad = vad;
        this.minSilenceWindows = Math.max(1, Math.round(minSilenceSec * SpeechModels.SAMPLE_RATE / WIN));
        this.maxSegmentWindows = Math.round(maxSegmentSec * SpeechModels.SAMPLE_RATE / WIN);
    }

    /** Feed exactly one 512-sample window; returns any segments that closed. */
    List<Segment> accept(float[] window) {
        float[] w = window.clone();
        float p = vad.compute(w);
        List<Segment> out = new ArrayList<>(1);

        if (!inSpeech) {
            if (p >= START_THRESHOLD) {
                inSpeech = true;
                silenceRun = 0;
                segmentStartWindow = windowIndex - preroll.size();
                for (float[] pre : preroll) {
                    windows.add(pre);
                    probs.add(0f);
                }
                preroll.clear();
                windows.add(w);
                probs.add(p);
            } else {
                preroll.addLast(w);
                if (preroll.size() > PREROLL_WINDOWS) preroll.removeFirst();
            }
        } else {
            windows.add(w);
            probs.add(p);
            silenceRun = p < CONTINUE_THRESHOLD ? silenceRun + 1 : 0;

            if (silenceRun >= minSilenceWindows) {
                int keep = windows.size() - silenceRun + TRAILING_PAD_WINDOWS;
                emit(Math.min(keep, windows.size()), false, out);
                // the silent tail becomes preroll for the next segment
                for (int i = Math.max(0, windows.size() - PREROLL_WINDOWS); i < windows.size(); i++) {
                    preroll.addLast(windows.get(i));
                }
                windows.clear();
                probs.clear();
                inSpeech = false;
            } else if (windows.size() >= maxSegmentWindows) {
                int cut = quietestWindowNearEnd();
                emit(cut + 1, true, out);
                List<float[]> restW = new ArrayList<>(windows.subList(cut + 1, windows.size()));
                List<Float> restP = new ArrayList<>(probs.subList(cut + 1, probs.size()));
                segmentStartWindow += cut + 1;
                windows.clear();
                probs.clear();
                windows.addAll(restW);
                probs.addAll(restP);
                silenceRun = 0;
            }
        }
        windowIndex++;
        return out;
    }

    /** Length of the segment that is still open, in samples (0 between segments). */
    int openSamples() {
        return inSpeech ? windows.size() * WIN : 0;
    }

    /** The audio of the open segment so far. */
    float[] openAudio() {
        float[] samples = new float[openSamples()];
        for (int i = 0; i < samples.length / WIN; i++) {
            System.arraycopy(windows.get(i), 0, samples, i * WIN, WIN);
        }
        return samples;
    }

    /**
     * Close the open segment here instead of waiting for a pause or the length cap; the speech
     * continues in a new segment. Returns null (and changes nothing) if there is too little speech.
     */
    Segment cutNow() {
        List<Segment> out = new ArrayList<>(1);
        if (inSpeech) emit(windows.size(), true, out);
        if (out.isEmpty()) return null;
        segmentStartWindow += windows.size();
        windows.clear();
        probs.clear();
        silenceRun = 0;
        return out.get(0);
    }

    /** End of stream: close any open segment. */
    List<Segment> flush() {
        List<Segment> out = new ArrayList<>(1);
        if (inSpeech && !windows.isEmpty()) {
            emit(windows.size(), false, out);
        }
        windows.clear();
        probs.clear();
        inSpeech = false;
        return out;
    }

    private int quietestWindowNearEnd() {
        int from = Math.max(MIN_SPEECH_WINDOWS, windows.size() - CUT_SEARCH_WINDOWS);
        int best = windows.size() - 1;
        float bestP = Float.MAX_VALUE;
        for (int i = from; i < windows.size(); i++) {
            if (probs.get(i) < bestP) {
                bestP = probs.get(i);
                best = i;
            }
        }
        return best;
    }

    private void emit(int count, boolean forced, List<Segment> out) {
        int speechWindows = 0;
        for (int i = 0; i < count; i++) {
            if (probs.get(i) >= START_THRESHOLD) speechWindows++;
        }
        if (speechWindows < MIN_SPEECH_WINDOWS) return;

        float[] samples = new float[count * WIN];
        for (int i = 0; i < count; i++) {
            System.arraycopy(windows.get(i), 0, samples, i * WIN, WIN);
        }
        out.add(new Segment(samples, segmentStartWindow * WIN, forced));
    }

    @Override
    public void close() {
        vad.release();
    }
}
