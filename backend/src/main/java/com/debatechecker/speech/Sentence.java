package com.debatechecker.speech;

/**
 * One complete sentence from one speaker — the unit that milestone 3 will classify
 * as fact / opinion / junk.
 *
 * @param speaker        session-local label, e.g. "S0"
 * @param text           the sentence
 * @param audioStartSec  where it starts in the captured audio (seconds since capture began)
 * @param audioEndSec    where it ends
 * @param latencyMs      time from the VAD closing the last segment of this sentence to now
 */
public record Sentence(String speaker, String text, double audioStartSec, double audioEndSec, long latencyMs) {}
