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
  three fragments, "Mr. Trump.", crosstalk). Latency does not drift: median 1.8–2.1 s in every
  3-minute window. But 25 of 262 sentences (about 10%) took over 5 s, worst 8.3 s — more than the
  short clips showed.
- **Open: the latency tail.** Mostly speaker changes and crosstalk, but some slow sentences sit
  inside one speaker's turn ("We also have to make the economy fairer." 7.2 s, "She's been doing
  this for 30 years." 7.3 s) and the cause of those has not been looked at. Start there.
- Milestone 3, part 1 ✅ claim gate (2026-10-05): every sentence gets FACT_CLAIM / OPINION / JUNK
  from a small fine-tuned classifier (`claims/ClaimGate`, ~7 ms on CPU) and the result is sent to
  the extension. Confirmed live in Chrome (session-c51d98d5, 5.5 min of the same debate).
- Milestone 3, part 2 ✅ rewriting (2026-10-05): sentences the gate passes are rewritten into
  standalone claims by gemma3:4b in Ollama, 100% on the GPU, and sent as {"type":"claim",...}.
  Verified by `Replay --realtime` on session-c51d98d5; not yet run live in Chrome. Quality is the
  weak point (see "Rewriting" under Decisions).
- Speaker names ✅ (2026-10-05): voices get names from how people are addressed, plus the video
  title (`claims/SpeakerNames`); sent as {"type":"speakers","names":{...}} and used in the LLM
  prompt. Checked on three transcripts of the one debate we have; the extension's new `tabTitle`
  field has not been run live yet.
- Ask-the-user names ✅ backend, ⚠ page card untested (2026-10-05, user's idea): when a new voice
  has said 6 words the backend sends {"type":"new-speaker","speaker","text","guess"}; the extension
  shows a card on the YouTube page (`extension/overlay.js`: name box, Moderator checkbox, Save /
  Skip) and answers {"type":"speaker-name","speaker","name","moderator"}. Tested with a script
  acting as the extension against the real server (prompts at the right moments, names used in
  later claims). The card itself has never been run in Chrome — check it first.
- **Open: rewriting faithfulness.** Still the weak point after names (see "Rewriting known
  limits"). Fix before milestone 4.
- Next: Milestone 4 (fact-checking), 5 (on-page overlay). See bottom.

## Layout
```
extension/            MV3: service-worker.js (icon click → tabCapture stream id → offscreen doc;
                      injects overlay.js and relays offscreen → page messages, "to-tab")
                      overlay.js (content script: "who is this new speaker?" card on the page)
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
  claims/ClaimGate           scores a sentence with models/claim-gate/gate.onnx; WordPieceTokenizer
                             is BERT's tokenizer in Java; GateCheck compares both against Python
  claims/ClaimRewriter       one Ollama /api/chat call per passed sentence (prompt + examples here)
  claims/ClaimPipeline       per session: gate every sentence, queue passed ones for the LLM on
                             one thread, keeps the last 4 lines (all speakers) as context
  claims/SpeakerNames        who S0/S1/S2 are; NamesCheck runs it over a saved Replay transcript
classifier/                  Python: compare.py (feature/model comparison), finetune.py (trains the
                             gate), export_onnx.py (writes models/claim-gate/ + data/parity.tsv).
                             data/, model/, .venv/ are gitignored; .venv has GPU PyTorch (cu126)
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
9 of 35 over 5 s); session-23e612c9 median 1754 / p90 3568 / max 5846, 1 of 47 over 5 s;
session-912c6eb1 (18 min) median 1921 / p90 4691 / max 8287, 25 of 262 over 5 s — by 3-minute
window the medians are 2036, 1866, 1834, 1875, 1903, 2113 and the counts over 5 s are 5, 0, 3, 4,
6, 7. Measure with the machine otherwise idle.
The eval audio was regenerated with sherpa-onnx 1.13.8 (pip), on which
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
- **Claim gate is a small classifier, not the LLM** (user's suggestion, 2026-10-05). The LLM is
  kept for rewriting (and milestone 4 verdicts) and only sees sentences the gate passes. Trained on
  ClaimBuster (Zenodo 3609356, CC-BY; labels non-factual / unimportant factual / check-worthy),
  split by debate: 2016 = test (includes our live recording), 2012 = dev, rest = train. On the 2016
  test set, macro F1: TF-IDF + XGBoost 0.52, TF-IDF + logreg 0.60, MiniLM embeddings + logreg 0.61,
  fine-tuned all-MiniLM-L6-v2 0.70 — so the fine-tuned model. XGBoost lost to logreg on both features.
- **Gate threshold 0.0506 on P(unimportant factual) + P(check-worthy)**, chosen on dev to keep 95%
  of check-worthy sentences. On test it passes 56.9% of sentences and keeps 94.2% of check-worthy
  (dev 90% target: passes 45.4%, keeps 87.8%; dev 98%: passes 69.1%, keeps 97.9%). The low threshold
  lets some opinions through ("That was a disaster." 0.07) and crosstalk fragments score as factual
  ("It's the single" 0.96). JUNK is only a rule: blocked and under 3 words.
- **Java gate must match Python**: `claims.GateCheck` on the 2,745 test sentences gave identical
  token ids, score difference ≤ 0.000003, same pass rate. Re-run it after retraining or touching
  the tokenizer (needs classifier/data/parity.tsv from export_onnx.py).
- **Rewriting: gemma3:4b, chosen for faithfulness** (`ollama pull gemma3:4b`). Compared on the 33
  passed sentences of session-c51d98d5 with the same prompt: qwen2.5:3b (median 400 ms) mostly
  copied the sentence and dropped real claims; llama3.2:3b (350 ms) resolved references but turned
  "can't bring" into "can bring" and lost "Mr. Trump"; gemma3:4b (720 ms alone) gave "Mr. Trump has
  not released his tax returns." and no meaning flips. All three run 100% on the GPU; gemma uses
  3.7 of the 4 GB, so nothing else fits beside it.
- **Rewriting prompt: one claim, minimal edit, 5 worked examples, 4 lines of context.** The first
  prompt (list of claims, 6 lines of context, no examples) made the 3B model rewrite the context
  instead of the sentence and invent facts. `num_gpu=99` so Ollama fails rather than spilling onto
  the CPU; `num_ctx` 2048, temperature 0, JSON schema output.
- **Rewriting numbers** (`--realtime`, session-c51d98d5): sentence latency unchanged by the LLM
  (median 2097 / p90 5848 / max 7774, 10 of 70 over 5 s; without it 2081 / 5915 / 7828, 10 of 70).
  Claim ready at median 3228 / p90 7819 / max 9375 ms after the audio, 7 of 32 over 5 s; the
  rewriting itself adds median 1080 / p90 1908 / max 2208 ms — over the 1 s budget, and it leaves
  under 2 s for a verdict at the median.
- **Rewriting known limits:** the model never returns "nothing to check", so moderator lines
  ("This is Secretary Clinton's two minutes, please.") and fragments come out as claims (only
  sentences under 4 words are skipped, by rule); "it"/"that" often stay unresolved ("It got us into
  the mess we were in in 2008 and 2009."); "you" can be resolved to the wrong side ("S2 proposed a
  tax benefit for S2's family"). Seen on d5ca3d24 with names on: a hypothetical turned into a
  statement ("it's one thing to have 20 trillion in debt, and our roads are good" → "The country's
  roads and bridges are in great shape ..."), invented detail ("650" → "$650,000"; "the report that
  said 650" → "Donald Trump's assets were worth $650 million"), and "Donald Trump stated that ..."
  wrappers around opinions.
- **Speaker names come from addresses, not from the LLM** (deterministic, no GPU time). "Mr.
  Trump?" followed by another voice saying ≥ 12 words = that voice is Trump (12 words because a
  new speaker's first short line often lands on a known voice). Saying a name counts against being
  that person; score = 2 × answered − mentioned; a name needs score ≥ 2 and a lead of 2, and is then
  kept while its score stays ≥ 1 (one stitched-in "Mr. Trump," un-named Trump before that). A voice
  that hands the floor to two different people is "the moderator". An address is a title + surname
  in the first words, the last words, or between commas.
- **Title use:** "Mr. Trump" becomes "Donald Trump" if the title has it; and if the title pairs a
  named person with one other name ("Hillary Clinton And Donald Trump"), the other name goes to the
  single remaining voice with ≥ 100 words that has not handed the floor to anyone or said that
  name. This is a guess by elimination — the riskiest rule here; drop it first if names go wrong.
  **It has only been tested with a made-up title.** The real title of the test video is "Full
  video: Trump-Clinton first presidential debate": no first names, no "X And Y", so on this video
  the title does nothing and names stay "Secretary Clinton" / "Mr. Trump" unless the user types them.
- **A name typed by the user wins** over everything worked out, and no other voice can then be
  worked out to have that surname. The automatic rules stay as the fallback for skipped voices.
  A voice is announced after 6 words, not at its first sentence, so the user has something to
  recognise and a stray fragment on a bogus voice never asks.
- **Moderator sentences are rewritten like anyone else's.** Skipping them was tried and undone the
  same day: live (session-374fafcc) it dropped "There's been a record six straight years of job
  growth ..." and "nearly half of Americans are living paycheck to paycheck". Procedural lines
  ("This is Secretary Clinton's two minutes, please.") therefore still come out as claims.
- **Names results** (`claims.NamesCheck`, same debate): 18-min run — Clinton at 25 s, Trump and
  moderator at 159 s; session-c51d98d5 — Trump 16 s, all three 121 s; session-d5ca3d24 — Trump (and
  with the title, Clinton) at 144 s. No wrong name and no change after assignment in any of them.
  Before names are known claims still say "He ..." / "S0 ...": in d5ca3d24 all of Clinton's claims
  come before 144 s. Rewriting with names (longer prompt, 6 examples) adds median 994 / p90 2111 /
  max 2766 ms on d5ca3d24.
- Latency = now − wall-clock time the sentence's audio ended. See "Latency baseline" under Testing.
  `Replay --realtime` paces against the clock; sleeping 100 ms per chunk fed audio at 0.9x on
  Windows and made latency appear to grow by 15 s over 150 s.

## Gotchas
- Compile/test against **sherpa-onnx v1.13.8** — newer Java API sources (e.g. OfflineRecognizerResult)
  don't match the v1.13.8 natives and crash the JVM in JNI.
- **Microsoft's onnxruntime.dll does not load in this JVM** ("DLL initialization routine failed"):
  it needs a newer Visual C++ runtime than the msvcp140.dll 14.36 in JDK 22's bin folder. ClaimGate
  therefore loads sherpa-onnx's natives first and sets `onnxruntime.native.onnxruntime.skip`, so
  the Java API runs on sherpa's onnxruntime.dll (1.28.2). Keep the `onnxruntime` dependency at 1.28.x.
- `-Dspring-boot.run.workingDirectory=.` resolved to `backend/` when tried on 2026-10-05 (models
  not found); an absolute path to the `debate-checker` folder worked.
- Call Ollama at `127.0.0.1`, not `localhost`: from Python the name lookup added ~2 s to every
  request on this machine. Ollama must be running (tray app); without it the backend still starts
  and classifies, and logs "Claim rewriting is off".
- The extension needs the `activeTab` permission, or `tab.url` is undefined. `scripting` +
  `activeTab` is what lets it inject overlay.js; the offscreen document has no `chrome.tabs`, so
  messages for the page go through the service worker.
- overlay.js must not use `innerHTML`: YouTube enforces Trusted Types.
- Capturing a tab mutes it; offscreen.js reconnects the source to `audioCtx.destination`.
- `ConcurrentWebSocketSessionDecorator` is required: sentences are sent from several speaker threads.
- "could not send sentence … session has been closed" at stop is harmless (last sentences flush
  after the extension closes the socket).

## Next milestones (planned, not started)
4. **Fact-checking (free):** Google Fact Check Tools API (ClaimReview) first, then Wikipedia/Wikidata
   APIs (or self-hosted SearXNG), then LLM verdict TRUE / FALSE / MISLEADING / UNVERIFIABLE with
   sources. Cache by claim embedding (debaters repeat themselves). Show "checking…" immediately.
5. **Overlay:** content script on youtube.com showing speaker, claim, verdict, sources, timestamp.
