# Debate Fact Checker

Milestone 1 (done): tab audio streams to the Java backend in real time.
**Milestone 2 (this version, v2.2): speaker-labeled sentences, using only free local models.**

```
YouTube tab ─▶ extension (16 kHz PCM, 100 ms chunks) ─▶ WebSocket ─▶ Spring Boot
   SessionPipeline (one virtual thread per tab)
     SpeechSegmenter ── Silero speech probability; cut at 0.3 s pauses, hard cap 5 s
     TitaNet fingerprints on 1 s windows every 0.25 s ── split where the voice changes
     Parakeet TDT 0.6B ── speech-to-text with word timestamps; words go to each speaker part
     SpeakerTracker ── "same voice as S0?" (cosine ≥ 0.45), else a new speaker
   SpeakerWorker (one virtual thread per speaker) ── stitches parts into sentences
 ─▶ JSON back to the extension, logged in the offscreen console
```

## What changed in v2.2 (after the second live test, on the real Trump–Clinton debate)

| Problem seen in the live log | Fix |
|---|---|
| "Well", "Yes.", a 1.7 s Holt line each became a bogus new speaker (S1, S3, S5) | Only parts ≥ 2.5 s, or ≥ 1.5 s *and* unlike everyone known (< 0.2), may create a speaker; short parts join the most similar known speaker and never change stored fingerprints |
| A new speaker's first words at the end of a cut segment got the previous speaker | Held back and re-identified together with the rest of the turn in the next segment |
| "back? Well," split in the wrong place | Speaker changes snap preferentially to a sentence end |
| Turns under 1.5 s couldn't be split off ("That is not true.") | Fingerprint windows 1 s every 0.25 s (turns ≥ 1 s) |
| Misheard words at cuts ("into a company" → "in 2015"), duplicates | Last 3 words of a cut segment are re-transcribed in the next one, keeping their speaker |
| Occasional empty transcript | Retry with 0.3 s, then 0.6 s silence padding (fixed 120/120 random cuts) |

Two synthetic test debates (3 voices, one with TV-style band-limiting, compression, hum, short
interjections and interruptions): every sentence correctly attributed except one 0.6 s "Yes.",
identical results for thresholds 0.35–0.55.

## What changed in v2.1 (after the first live test)

| Problem seen in the live log | Cause | Fix |
|---|---|---|
| Segments of 20+ s, sentences arriving 2–20 s late | sherpa-onnx's `max_speech_duration` is a soft limit | Own segmenter (`SpeechSegmenter`) with a hard 5 s cap, cut at the quietest point |
| Moderator + debater in one segment, one label | Handoffs often have no pause | Speaker-change detection inside each segment, split text by word timestamps |
| Trump and Clinton merged into S0 | CAM++ fingerprints: different voices scored up to 0.93 | Tested 7 free models; **TitaNet-small**: same voice ≥ 0.72, different ≤ 0.19 |
| Words cut in half / lost at forced cuts | Cut lands mid-word | Last word of a cut segment is carried into the next; repeats removed |
| `with$14 million` | ASR spacing quirk | Text tidy-up |

On a synthetic 3-voice debate (moderator + two debaters, handoffs with 0.1–0.15 s gaps), every
sentence gets the right speaker for any threshold from 0.3 to 0.6, and sentence latency is mostly
1.2–3 s. Real broadcast audio is harder; use the replay tool below to check yours.

## Setup

1. **Get the new speaker model.** Re-run from the `debate-checker` folder (it skips files you already have):
   ```powershell
   powershell -ExecutionPolicy Bypass -File .\download-models.ps1
   ```
   You need: `models\silero_vad.onnx`, `models\nemo_en_titanet_small.onnx`,
   `models\sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8\` (encoder/decoder/joiner `.int8.onnx` + `tokens.txt`).
   The old `3dspeaker_speech_campplus...onnx` can be deleted.
2. Run `DebateCheckerApplication` with the working directory set to the `debate-checker` folder.
3. Reload the extension on `chrome://extensions`.

## Live test

Backend log, one block per segment, then finished sentences:
```
[a1b2c3d4] segment 16.1-20.5s (cut): 2 part(s), speaker 88ms, asr 926ms
      S1 16.1-19.0s (-0.01 NEW): Secretary, would you like to respond?
      S2 19.0-20.5s (0.11 NEW): Well, I think trade is an
[a1b2c3d4] SENTENCE [S1] (1243 ms) Secretary, would you like to respond?
```
`(cut)` = the segment hit the 5 s cap. The number after each part is its similarity to the
speaker it was matched with (`NEW` = no match, new label). `SENTENCE (… ms)` = time from the end
of that speech to the sentence being ready.

Sentences also show up in Chrome: `chrome://extensions` → this extension → *Inspect views: offscreen.html* → Console.

## Replay tool: tune without replaying YouTube

Every live session is saved to `recordings\session-<id>.wav`. Run it back through the same pipeline:

IntelliJ → open `backend/src/main/java/com/debatechecker/tools/Replay.java` → green ▶ next to `main`
→ it fails once without arguments → *Edit Configurations*:
- Program arguments: `recordings\session-e2b1228e.wav 0.45`  (add `--realtime` to measure latency)
- Working directory: the `debate-checker` folder

## Tuning (`application.properties`)

| What you see | Change |
|---|---|
| One person split into S0, S2, S3… | lower `speaker-threshold` (0.4, 0.35) |
| Two people merged into one label | raise `speaker-threshold` (0.5, 0.55) |
| `asr` times over ~1500 ms | raise `asr-threads` to 6 |
| Sentences chopped mid-thought | raise `vad-min-silence-seconds` to 0.4 |

Known limits: crosstalk goes to whoever is louder; a turn under ~1 s ("Yes.", "No!") goes to a
neighbouring speaker; labels are per session (S0 in one run may be S1 in the next).

## Next: milestone 3
Classify each sentence as FACT_CLAIM / OPINION / JUNK with a local LLM on the RTX 3050 (Ollama),
using the speaker's recent context from `SpeakerWorker` to turn "he cut it by half" into a standalone claim.
