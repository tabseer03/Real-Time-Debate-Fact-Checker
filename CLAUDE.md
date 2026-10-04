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
- Milestone 2 ✅ (v2.2) speaker-labelled sentences from free local models. Verified on two synthetic
  debates (`eval/`). Live run on the real 2016 Trump–Clinton debate
  (https://www.youtube.com/watch?v=s7gDXtRS0jo) with v2.1 got long turns right but short turns created
  bogus speakers; v2.2 fixes that but **has not yet been confirmed live** — do that first.
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
**v2.2 baseline:** debate = every sentence correct; debate2 = all correct except one 0.6 s "Yes."
(too short to fingerprint). Same result for thresholds 0.35–0.55. Re-run both after any change to
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
- **Short parts can't invent speakers** (live test: "Well", "Yes.", a 1.7 s moderator line became
  new speakers). New speaker only from parts ≥ 2.5 s, or ≥ 1.5 s with similarity < 0.2 to everyone.
  Short parts join the nearest known speaker (≥ 0.2) without updating its centroid, else a neighbour.
  An unidentified last part of a cut segment is held back and re-identified in the next segment.
- **Forced cuts:** last 3 words are held back and re-transcribed in the next segment (fixes
  mid-word cuts, mishearings like "into a company" → "in 2015"); they keep their original speaker
  label and are excluded from speaker analysis there. Repeated words at the join are de-duplicated.
- **Parakeet sometimes returns "" for abrupt segments** (~1/100). Retry with 0.3 s then 0.6 s
  silence padding fixed 120/120 random cuts. Padding everything up front is worse.
- **SpeakerWorker flushing:** emit complete sentences; flush an unfinished one when another speaker
  starts, or after 2 s quiet if the last segment ended at a pause (8 s after a cut). Run-ons over
  30 words are released at the last comma (latency).
- Latency = now − wall-clock time the sentence's audio ended. Measured 1.2–3 s typical.

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
