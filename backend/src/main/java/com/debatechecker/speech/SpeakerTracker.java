package com.debatechecker.speech;

import java.util.ArrayList;
import java.util.List;

/**
 * Online speaker clustering for one session.
 *
 * Every segment's embedding is compared with the running average ("centroid") of each speaker
 * seen so far. Close enough to the best match: same speaker, and the centroid is nudged toward
 * the new sample. Otherwise: a new speaker label (S0, S1, ...), up to maxSpeakers.
 *
 * Not thread-safe; a session's pipeline thread is its only user.
 */
public final class SpeakerTracker {

    public record Match(String label, float similarity, boolean isNewSpeaker) {}

    private final float threshold;
    private final int maxSpeakers;
    private final List<float[]> centroids = new ArrayList<>();
    private final List<Integer> counts = new ArrayList<>();

    public SpeakerTracker(float threshold, int maxSpeakers) {
        this.threshold = threshold;
        this.maxSpeakers = maxSpeakers;
    }

    /** Assign, creating a new speaker if nothing is similar enough (and there is room). */
    public Match assign(float[] embedding) {
        return assign(embedding, true);
    }

    /** Closest known speaker without changing anything; null if no speakers yet. */
    public Match nearest(float[] embedding) {
        float[] e = normalized(embedding);
        int best = -1;
        float bestSim = -1f;
        for (int i = 0; i < centroids.size(); i++) {
            float sim = dot(e, centroids.get(i));
            if (sim > bestSim) {
                bestSim = sim;
                best = i;
            }
        }
        return best < 0 ? null : new Match(label(best), bestSim, false);
    }

    public Match assign(float[] embedding, boolean allowNew) {
        float[] e = normalized(embedding);

        int best = -1;
        float bestSim = -1f;
        for (int i = 0; i < centroids.size(); i++) {
            float sim = dot(e, centroids.get(i));
            if (sim > bestSim) {
                bestSim = sim;
                best = i;
            }
        }

        boolean matched = best >= 0 && bestSim >= threshold;
        boolean full = centroids.size() >= maxSpeakers;
        if (matched || ((full || !allowNew) && best >= 0)) {
            update(best, e);
            return new Match(label(best), bestSim, false);
        }

        centroids.add(e);
        counts.add(1);
        int idx = centroids.size() - 1;
        return new Match(label(idx), best >= 0 ? bestSim : 1f, true);
    }

    public float threshold() {
        return threshold;
    }

    public int speakerCount() {
        return centroids.size();
    }

    private void update(int idx, float[] e) {
        // Running mean, capped so a speaker's centroid keeps adapting (mic changes, emotion)
        // instead of freezing after the first few minutes.
        int n = Math.min(counts.get(idx), 50);
        float[] c = centroids.get(idx);
        for (int i = 0; i < c.length; i++) {
            c[i] = (c[i] * n + e[i]) / (n + 1);
        }
        centroids.set(idx, normalized(c));
        counts.set(idx, counts.get(idx) + 1);
    }

    private static String label(int idx) {
        return "S" + idx;
    }

    private static float dot(float[] a, float[] b) {
        float s = 0f;
        for (int i = 0; i < a.length; i++) s += a[i] * b[i];
        return s;
    }

    private static float[] normalized(float[] v) {
        double norm = 0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        float[] out = new float[v.length];
        if (norm == 0) return out;
        for (int i = 0; i < v.length; i++) out[i] = (float) (v[i] / norm);
        return out;
    }
}
