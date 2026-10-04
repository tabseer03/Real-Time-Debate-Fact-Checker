# Debate Fact Checker — project notes for Claude Code

Chrome extension + Java backend that fact-checks YouTube debates in real time. Each speaker is
handled separately; every sentence is classified FACT_CLAIM / OPINION / JUNK, and claims are
fact-checked. **Target: < 5 s from a claim being spoken to its verdict on screen.**

## Constraints from the user (keep to these)
- **Java** for the backend (Spring Boot 3, Java 21+; the user runs JDK 22). Plain JS for the extension.
- **Free only**: local open models or free tiers; no paid APIs.
- Hardware: Windows, Ryzen 5 5600H (6c/12t), 16 GB RAM, RTX 3050 4 GB. Speech models run on CPU;
  the GPU is reserved for the local LLM (milestone 3).
- The user works in IntelliJ; the project root is the `debate-checker` folder.

## Status
- Milestone 1 ✅ tab audio → backend (16 kHz mono PCM, 100 ms chunks, ~1.00x realtime).
- Milestone 2 ✅ speaker-labelled sentences from free local models. Verified on two synthetic
  debates (`eval/`) and confirmed live on the real 2016 Trump–Clinton debate
  (https://www.youtube.com/watch?v=s7gDXtRS0jo), three clips of 1–3 minutes (2026-10-04): no bogus
  speakers, every long sentence on the right speaker, median latency 1.9–2.7 s.
- Known limits, all at interruptions and crosstalk: a line under ~1 s ("No.", "Mr. Trump.") goes to
  whoever is speaking around it; a new speaker's first words go to a known voice until they have
  spoken 2.5 s; overlapping speech gives fragments and sometimes loses words; sentences at a
  speaker change can take 5–7 s.
- Long run ✅ 18 minutes from the start of the same debate (session-912c6eb1, 2026-10-04): three
  speakers (Holt, Clinton, Trump) with the same labels throughout, none added by audience noise;
  262 sentences; fast replay 2.1x realtime. Errors were only the known limits above (Trump's first
  three fragments, "Mr. Trump.", crosstalk). Latency over the full 18 minutes has not been measured.
- Next: Milestone 3 (classification), 4 (fact-checking), 5 (on-page overlay). See bottom.

## Layout
```
extension/            MV3: service-worker.js (icon click → tabCapture stream id → offscreen doc)
                      offscreen.js (getUserMedia(tab) → AudioWorklet → WebSocket ws://localhost:8080/audio)
                      pcm-worklet.js (mono, resample to 16 kHz, Int16, 100 ms = 3200-byte chunks)
backend/              Spring Boot; pom pulls sherpa-onnx v1.13.8 from JitPack (+ win-x64 natives)
  audio/AudioStreamHandler   WebSocket ↔ SessionPipeline; sends {"type":"sentence",...} JSON back
  audio/AudioSession         saves recordings/session-<id>.wav (every live run is replayable)
  speech/SpeechModels        loads models once; ASR + speaker embedding behind locks
  speech/SpeechSegmenter     own segmentation on Silero probabilities (start ≥0.5, continue ≥0.35,
                             end after 0.3 s silence, HARD cap 5 s at the quietest window)
  speech/SessionPipeline     per tab, one virtual thread: segment → speaker-change points →
                             ASR with word timestamps → parts → speaker labels → SpeakerWorker
  speech/SpeakerTracker      online clustering, running-mean centroids, cosine threshold 0.45
  speech/SpeakerWorker       one virtual thread per speaker: stitch parts into sentences,
                             keeps last 5 sentences as context (for milestone 3 claim rewriting)
  tools/Replay               runs a .wav through the same pipeline (fast, or --realtime for latency)
models/                      (gitignored) silero_vad.onnx, nemo_en_titanet_small.onnx,
                             sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/ — fetch with download-models.ps1
eval/                        synthetic test debates + ground truth (see "Testing")
```

## Run / test
Run everything from the `debate-checker` folder (relative `models/` and `recordings/` paths).
- Backend: IntelliJ run config for `DebateCheckerApplication`, working dir = `debate-checker`. Or:
  `mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.workingDirectory=."`
  (`mvn` may not be on PATH — IntelliJ has bundled Maven; ask the user if unsure.)
- Replay a recording (the main tuning loop — no YouTube needed):
  `mvn -f backend/pom.xml -q compile exec:java "-Dexec.mainClass=com.debatechecker.tools.Replay" "-Dexec.args=recordings/session-XXXX.wav 0.45"`
  Add `--realtime` to the args to measure per-sentence latency.
- Extension: chrome://extensions → Load unpacked `extension/`; reload after any change. Sentences
  are logged in the offscreen document's console (Inspect views: offscreen.html).

### Testing
`eval/make_debate.py` and `eval/make_debate2.py` synthesize 3-voice debates with ground truth
(`eval/*_truth.json`). debate2 is TV-like (band-pass, compression, hum, short interjections).
Needs `pip install sherpa-onnx numpy scipy` and Piper voices in `eval/voices/`
(vits-piper-en_US-ryan-medium, en_US-amy-medium, en_GB-alan-medium from the sherpa-onnx `tts-models`
release). Run from the project root, then Replay `eval/debate.wav` / `eval/debate2.wav`.
**Baseline (with early sentence cuts):** debate = every sentence correct; debate2 = all correct
except the 0.6 s "Yes." (it now opens a segment, so it can't be split off) and a missing "Good."
(the speech model drops it). Same for thresholds 0.35, 0.45, 0.55 on both. On the two live
recordings the speakers are stable across thresholds but wording and a few crosstalk fragments
differ, because the threshold decides where early cuts happen. Sentences from different speakers
can print slightly out of order (separate worker threads).
**Latency baseline** (`--realtime`, ms from the end of a sentence's audio): session-17b162f1
median 1870 / p90 4315 / max 5725, 2 of 41 over 5 s (before early cuts: median 3209, max 6012,
9 of 35 over 5 s); session-23e612c9 median 1754 / p90 3568 / max 5846, 1 of 47 over 5 s. The eval audio was regenerated with sherpa-onnx 1.13.8 (pip), on which
committed v2.2 also gave "Secretary, your response." to the wrong speaker. Re-run both after any change to
segmentation or speaker logic and compare against this.

## Decisions and why (don't undo without re-testing)
- **TitaNet-small for speaker embeddings.** 7 free sherpa-onnx models were compared on clean
  segments: CAM++ (the first choice) had different speakers up to 0.93 similarity — unusable.
  TitaNet-small: same ≥ 0.72, different ≤ 0.19. On 1 s windows: same ≥ 0.50, different ≤ 0.26.
- **Own segmenter.** sherpa-onnx `max_speech_duration` is soft (raises the threshold), so segments
  ran to 20 s. Our cap is hard. 5 s beat 4 s (4 s cut more words and misattributed one).
- **Speaker-change detection inside segments.** Handoffs often have no pause. 1 s windows every
  0.25 s; recursive split where mean(left)·mean(right) < 0.45, both sides ≥ 1 s; boundaries snap to
  the word gap (bonus after . ? !).
- **Interjections are split off as an island** (`splitOffInterjection`). In "B | MOD 1.6 s | B" the
  voice is the same on both sides, so no single boundary scores below 0.45 and the moderator's line
  was given to B. When no single boundary is found, the stretch (≥ 1 s) least like everything around
  it is cut out if that similarity is < 0.45. Extra splits inside one speaker's speech are harmless
  (same label). An island under 0.8 s ("Yes.") is fingerprinted from the 1 s window around it
  instead of going to a neighbour — right on debate2, but only at similarity 0.20, the join minimum.
- **Segments close as soon as a sentence ends** (`cutAtSentenceEnd`). A sentence ending early in a
  5 s segment waited for the segment to close. The open segment is now transcribed at 1.5 s and
  then every 1 s; a full stop followed by two words (the first capitalised, not after a title) closes
  it there, and the words after it are carried as at any forced cut, but with no speaker assumed
  (turns change at sentence ends). Only done when the part before the cut is ≥ 2.5 s or matches a
  known speaker at the threshold — otherwise a new voice's short first sentence would be pinned on
  someone else. Cost: about 3x the speech-to-text work (fast replay 7.6x → ~2.5x realtime).
- **Speaker changes snap to gaps between all words, held-back ones included.** Snapping only to
  the kept words moved a change that coincided with the cut one gap earlier ("the best ever | at it.").
- **Short parts can't invent speakers** (live test: "Well", "Yes.", a 1.7 s moderator line became
  new speakers). New speaker only from parts ≥ 2.5 s, or ≥ 1.5 s with similarity < 0.2 to everyone.
  Short parts join the nearest known speaker (≥ 0.2) without updating its centroid, else a neighbour.
  An unidentified last part of a cut segment is held back and re-identified in the next segment.
- **Forced cuts:** last 3 words are held back and re-transcribed in the next segment (fixes
  mid-word cuts, mishearings like "into a company" → "in 2015"); they keep their original speaker
  label and are excluded from speaker analysis there. Repeated words at the join are de-duplicated.
- **Carried words are checked against their first hearing** (`reconcileWithFirstHearing`). The
  abrupt start of the carry drops its first word ("how do you" → "Do you"), turns the end of the
  previous word into a word ("And she started"), or capitalises mid-sentence; all three are repaired
  from the words heard before the cut. The cut also stays after the last kept word — word starts can
  be closer than the 0.08 s lead-in, which silently dropped that word ("that you [have] to come back").
- **Carry is capped at 2 s** and a transcript that stops > 2 s before the segment end gets its tail
  transcribed separately. Under crosstalk Parakeet returned 6 words for 5 s of audio; holding back
  "the last 3 words" then carried 4.4 s and made an 8 s segment. (The tail retry is a safety net:
  it did not fire on the Trump–Clinton recording once the lead-in changed.)
- **Sentences don't break after titles** (Mr. Mrs. Ms. Dr. Sen. Gov. Rep. Gen. Prof. St. vs.).
- **Parakeet sometimes returns "" for abrupt segments** (~1/100). Retry with 0.3 s then 0.6 s
  silence padding fixed 120/120 random cuts. Padding everything up front is worse.
- **SpeakerWorker flushing:** emit complete sentences; flush an unfinished one when another speaker
  starts, or after 2 s quiet if the last segment ended at a pause (8 s after a cut). Run-ons over
  30 words are released at the last comma (latency).
- Latency = now − wall-clock time the sentence's audio ended. See "Latency baseline" under Testing.
  `Replay --realtime` paces against the clock; sleeping 100 ms per chunk fed audio at 0.9x on
  Windows and made latency appear to grow by 15 s over 150 s.

## Gotchas
- Compile/test against **sherpa-onnx v1.13.8** — newer Java API sources (e.g. OfflineRecognizerResult)
  don't match the v1.13.8 natives and crash the JVM in JNI.
- The extension needs the `activeTab` permission, or `tab.url` is undefined.
- Capturing a tab mutes it; offscreen.js reconnects the source to `audioCtx.destination`.
- `ConcurrentWebSocketSessionDecorator` is required: sentences are sent from several speaker threads.
- "could not send sentence … session has been closed" at stop is harmless (last sentences flush
  after the extension closes the socket).

## Next milestones (planned, not started)
3. **Classification** with a local LLM via Ollama on the RTX 3050 (4 GB VRAM → ~3–4B model at Q4,
   e.g. Qwen2.5-3B or Llama-3.2-3B). Per sentence: rewrite into standalone claim(s) using the
   speaker's recent context (SpeakerWorker.recentContext), then FACT_CLAIM / OPINION / JUNK as JSON.
   Run it async per speaker so it never blocks transcription. Budget: < 1 s.
4. **Fact-checking (free):** Google Fact Check Tools API (ClaimReview) first, then Wikipedia/Wikidata
   APIs (or self-hosted SearXNG), then LLM verdict TRUE / FALSE / MISLEADING / UNVERIFIABLE with
   sources. Cache by claim embedding (debaters repeat themselves). Show "checking…" immediately.
5. **Overlay:** content script on youtube.com showing speaker, claim, verdict, sources, timestamp.
