# Real-Time Debate Fact Checker

A Chrome extension and a Java backend that listen to a YouTube debate while it plays, work out who
is speaking, and pick out the factual claims worth checking. Everything runs on your own machine
with free, open models: no paid APIs.

What it does today:

- **Transcribes each speaker separately**, sentence by sentence, about 2 seconds behind the video.
- **Names the speakers.** A card on the YouTube page asks who each new voice is; for voices you
  skip, names are worked out from how people are addressed ("Mr. Trump?", "Secretary Clinton, ...").
- **Labels every sentence** `FACT_CLAIM`, `OPINION` or `JUNK` with a small classifier (about 7 ms).
- **Rewrites each claim so it stands alone** ("They're going to Mexico." → "American jobs are
  fleeing to Mexico.") with a local LLM, and checks the rewrite against what was actually said.

- **Reports once a minute, speaker by speaker**: what each one claimed in that minute and what the
  check found for each claim.
- **Looks each claim up** in fact-checks already published (PolitiFact, FactCheck.org and others,
  through Google's Fact Check Tools API) and takes the verdict from their rating. A claim with no
  published fact-check gets `UNVERIFIABLE` with related Wikipedia passages — which, so far, is
  nearly every claim (see [Known limits](#known-limits)).

Not built yet: the on-page display of results (see [Roadmap](#roadmap)). For now the output appears
in the extension's console and the backend log.

## How it works

```
YouTube tab ─▶ extension (16 kHz PCM, 100 ms chunks) ─▶ WebSocket ─▶ Spring Boot backend

 speech/   SessionPipeline (one virtual thread per tab)
             SpeechSegmenter ── Silero speech probability; cut at 0.3 s pauses, hard cap 5 s
             TitaNet fingerprints on 1 s windows every 0.25 s ── split where the voice changes
             Parakeet TDT 0.6B ── speech-to-text with word timestamps
             SpeakerTracker ── "same voice as S0?" (cosine ≥ 0.45), else a new speaker
           SpeakerWorker (one virtual thread per speaker) ── stitches parts into sentences

 claims/   ClaimPipeline (per tab)
             ClaimGate ── fine-tuned MiniLM classifier: is this sentence factual at all?
             SpeakerNames ── who S0, S1, ... are
             ClaimRewriter ── gemma3:4b in Ollama rewrites passed sentences (GPU, own thread)
             ClaimChecker ── rejects rewrites that add or change things; falls back to the
                             speaker's own words

 factcheck/ FactChecker (one for the server; remembers claims it has already checked)
             GoogleFactCheck ── published fact-checks of this claim; their rating is the verdict
             WikipediaEvidence ── otherwise: passages from the three best-matching pages

 ─▶ JSON back to the extension: sentences, claims, verdicts, speaker names, "who is this?" prompts
```

Speech models run on the CPU. The LLM runs entirely on the GPU, so the two do not compete.
Fact-checking is network calls only.

## Requirements

- Windows 10/11, x64. (The speech library's native files in `backend/pom.xml` are the Windows
  ones; the comment there names the Linux and macOS equivalents.)
- JDK 21 or newer, and Maven (IntelliJ's bundled Maven is enough).
- Chrome.
- [Ollama](https://ollama.com), and an NVIDIA GPU with 4 GB of memory for `gemma3:4b`.
- Python 3.12, only to build the claim classifier once.

Developed on a Ryzen 5 5600H, 16 GB RAM, RTX 3050 4 GB.

## Setup

Run every command from the project folder (the one containing `backend`, `extension`, `models`).

### 1. Speech models

```powershell
powershell -ExecutionPolicy Bypass -File .\download-models.ps1
```

This fetches `models\silero_vad.onnx`, `models\nemo_en_titanet_small.onnx` and
`models\sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8\` (several hundred MB). It skips files you
already have.

### 2. Claim classifier

The classifier is trained on the [ClaimBuster dataset](https://zenodo.org/records/3609356)
(CC BY 4.0), about 23,000 sentences from US presidential debates. It is not downloadable ready-made,
so build it once:

```powershell
New-Item -ItemType Directory -Force classifier\data | Out-Null
foreach ($f in 'crowdsourced.csv','groundtruth.csv') {
    Invoke-WebRequest "https://zenodo.org/api/records/3609356/files/$f/content" -OutFile "classifier\data\$f"
}

python -m venv classifier\.venv
classifier\.venv\Scripts\python -m pip install torch --index-url https://download.pytorch.org/whl/cu126
classifier\.venv\Scripts\python -m pip install transformers pandas scikit-learn onnx onnxruntime

classifier\.venv\Scripts\python classifier\finetune.py
classifier\.venv\Scripts\python classifier\export_onnx.py
```

Training takes about 3 minutes on the RTX 3050 and about 45 minutes on a CPU (leave out the
`--index-url` part to install the CPU build of PyTorch). The result is `models\claim-gate\`
(`gate.onnx`, `vocab.txt`, `gate.json`). The backend will not start without it.

### 3. LLM

Install Ollama, keep it running (the llama icon in the system tray), and fetch the model once:

```powershell
ollama pull gemma3:4b
```

Without Ollama the backend still starts and classifies sentences; it logs
`Claim rewriting is off` and sends no claims.

### 4. Fact-check key

Verdicts come from Google's Fact Check Tools API, which is free but needs a key:
[console.cloud.google.com](https://console.cloud.google.com) → create a project → *APIs & Services*
→ enable **Fact Check Tools API** → *Credentials* → *Create credentials* → *API key*.

Give it to the backend as the environment variable `GOOGLE_FACTCHECK_KEY` (IntelliJ: the run
configuration's *Environment variables*, name typed without quotes; PowerShell:
`$env:GOOGLE_FACTCHECK_KEY = "..."`). Do not
put it in a file that is committed. Without a key the backend logs `No Google Fact Check key` and
every claim comes back `UNVERIFIABLE` with Wikipedia passages only.

### 5. Backend

In IntelliJ, run `DebateCheckerApplication` with the working directory set to the project folder.
Or from a terminal, with the full path to the project folder:

```powershell
mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.workingDirectory=C:\path\to\project"
```

It is ready when the log shows `Started DebateCheckerApplication` and
`LLM gemma3:4b loaded, 100% on the GPU`.

### 6. Extension

`chrome://extensions` → turn on *Developer mode* → *Load unpacked* → choose the `extension` folder.
Reload it there after any change to its files.

## Using it

1. Open a debate on YouTube and click the extension icon. The badge shows `ON`.
2. When a new voice has said a few words, a card appears at the top right of the page with a quote
   of what it said. Type the name, tick *Moderator* if it is one, and press *Save* (or *Skip*).
3. Watch the output: `chrome://extensions` → this extension → *Inspect views: offscreen.html* →
   *Console*.
4. Click the icon again to stop.

```
NEW SPEAKER S2: Thank you, Lester. Our jobs are fleeing the country.
SPEAKERS S0 = Lester Holt (moderator), S1 = Hillary Clinton, S2 = Donald Trump
[S2] (1390 ms) FACT_CLAIM They're going to Mexico.
[S2] (2821 ms) CLAIM American jobs are fleeing to Mexico.
[S2] (1872 ms) OPINION You look at what China is doing to our country in terms of making our product.
[S2] (2537 ms) CLAIM (as said) When you look at what's happening in Mexico, a friend of mine ...
```

The number in brackets is the delay from the end of that speech to the line being ready.
`CLAIM (as said)` means the LLM's rewrite failed the check and the speaker's own words are used.

The backend sends these messages over the WebSocket (`ws://localhost:8080/audio`):

| `type` | Fields | When |
|---|---|---|
| `sentence` | `speaker`, `text`, `start`, `end`, `latencyMs`, `category`, `factual` | every finished sentence |
| `claim` | `id`, `speaker`, `speakerName`, `claim`, `rewritten`, `sentence`, `start`, `end`, `latencyMs` | each `FACT_CLAIM`, about a second later |
| `digest` | `from`, `to` (seconds), `speakers`: each `speaker`, `name`, `statements`; each statement `id` (the claim's), `claim`, `sentence`, `start`, `verdict` (`TRUE` / `FALSE` / `MISLEADING` / `UNVERIFIABLE`), `status` ("confirmed", "contradicted", "misleading", "could not be verified"), `rating` (the fact-checker's own words, or `""`), `sources` (each `source`, `title`, `url`, `text`, `rating`), `repeated` | once per minute of the debate that had claims, about 10 s after the minute ends |
| `speakers` | `names` (label → name) | whenever a name is learned or typed |
| `new-speaker` | `speaker`, `text`, `guess` | a voice not heard before has said 6 words |

The extension answers a `new-speaker` message with
`{"type":"speaker-name","speaker":"S2","name":"Donald Trump","moderator":false}`.

## Replay: tune without replaying YouTube

Every live session is saved to `recordings\session-<id>.wav`. Run one back through the same
pipeline, as fast as the machine allows:

```powershell
mvn -f backend/pom.xml -q compile exec:java "-Dexec.mainClass=com.debatechecker.tools.Replay" "-Dexec.args=recordings/session-XXXX.wav 0.45"
```

Add to the arguments:

- `--realtime` to feed the audio at playback speed and print each line's latency.
- `--name=S2=Donald_Trump` or `--name=S0=Lester_Holt,moderator` to name a voice as you would on the
  card (underscores for spaces).
- `--title=Some_Video_Title` to pass the tab title.

In IntelliJ: open `backend/src/main/java/com/debatechecker/tools/Replay.java`, press the green ▶
next to `main`, then *Edit Configurations* → program arguments as above, working directory = the
project folder.

Two smaller tools check one part each: `claims.GateCheck` compares the Java classifier with the
Python one, and `claims.NamesCheck` runs the speaker naming over a saved Replay transcript.

## Results so far

Measured on recordings of two debates (2016 Trump–Clinton, 2012 Obama–Romney town hall), replayed
at real-time speed:

| | Typical | Notes |
|---|---|---|
| Sentence delay | median about 2 s | 10–14% of sentences take over 5 s, mostly at speaker changes |
| Claim delay | median about 3 s | rewriting adds about 1 s |
| Verdicts | once a minute | a digest per speaker, about 10 s after each minute of the debate ends |
| Claims that get a verdict | 0 of 135 and 0 of 31 on two recordings | see [Known limits](#known-limits) |
| Claim classifier | keeps 94% of check-worthy sentences, passes 57% of all sentences | held-out 2016 debates from ClaimBuster |
| Rewrites that pass the check | 45–60% | the rest are sent as said |

## Known limits

- **Crosstalk and interruptions.** Overlapping speech gives fragments; a line under about 1 second
  ("No.", "Mr. Trump.") goes to whoever is speaking around it; a new speaker's first few words can
  land on a voice that is already known.
- **Rewrites can still be wrong.** The check compares words, not meaning, so a rewrite that only
  rearranges the speaker's words gets through. "I" inside a story someone is quoting is pinned on
  the person telling it.
- **Not everything labelled a claim is one.** Thanks and pleasantries from debaters still come out
  as claims. A moderator's housekeeping is filtered once the voice is marked as a moderator.
- **In practice almost every claim is `UNVERIFIABLE` today.** A verdict needs a published
  fact-check of the same claim, and Google's index rarely has one for a sentence as it was spoken:
  on an 18-minute recording of the 2016 debate, 135 claims got 0 verdicts (for 127 Google returned
  nothing at all). Claims worded the way fact-checkers word them do match (2 of 16 in
  `eval/claims.txt`). The local 4B model was tried as the judge for the rest and got too many
  wrong, even with the right passage in front of it. The Wikipedia passages are picked by shared words and are often
  beside the point; figures and "this year" claims find nothing.
- **Wikipedia allows roughly 10–15 lookups a minute** from one connection; past that, claims get no
  passages for half a minute.
- **Speaker labels are per session.** S0 in one run may be S1 in the next.
- **English only**, and tested on two US presidential debates.

## Tuning (`backend/src/main/resources/application.properties`)

| What you see | Change |
|---|---|
| One person split into S0, S2, S3… | lower `speaker-threshold` (0.4, 0.35) |
| Two people merged into one label | raise `speaker-threshold` (0.5, 0.55) |
| `asr` times over ~1500 ms in the log | raise `asr-threads` to 6 |
| Sentences chopped mid-thought | raise `vad-min-silence-seconds` to 0.4 |
| A different LLM | `llm-model` (and `ollama pull` it) |

The reasons behind the design choices, with the measurements, are in [CLAUDE.md](CLAUDE.md).

## Roadmap

- **Verdicts for claims nobody has fact-checked.** Needs a judge better than a 4B local model
  (a larger model on a free tier, or a small entailment model on the CPU) and better evidence than
  word-matched Wikipedia passages.
- **On-page overlay.** Speaker, claim, verdict and sources shown on the YouTube page.

## History of the speech pipeline

### v2.2 (after the second live test, on the real Trump–Clinton debate)

| Problem seen in the live log | Fix |
|---|---|
| "Well", "Yes.", a 1.7 s Holt line each became a bogus new speaker (S1, S3, S5) | Only parts ≥ 2.5 s, or ≥ 1.5 s *and* unlike everyone known (< 0.2), may create a speaker; short parts join the most similar known speaker and never change stored fingerprints |
| A new speaker's first words at the end of a cut segment got the previous speaker | Held back and re-identified together with the rest of the turn in the next segment |
| "back? Well," split in the wrong place | Speaker changes snap preferentially to a sentence end |
| Turns under 1.5 s couldn't be split off ("That is not true.") | Fingerprint windows 1 s every 0.25 s (turns ≥ 1 s) |
| Misheard words at cuts ("into a company" → "in 2015"), duplicates | Last 3 words of a cut segment are re-transcribed in the next one, keeping their speaker |
| Occasional empty transcript | Retry with 0.3 s, then 0.6 s silence padding (fixed 120/120 random cuts) |

### v2.1 (after the first live test)

| Problem seen in the live log | Cause | Fix |
|---|---|---|
| Segments of 20+ s, sentences arriving 2–20 s late | sherpa-onnx's `max_speech_duration` is a soft limit | Own segmenter (`SpeechSegmenter`) with a hard 5 s cap, cut at the quietest point |
| Moderator + debater in one segment, one label | Handoffs often have no pause | Speaker-change detection inside each segment, split text by word timestamps |
| Trump and Clinton merged into S0 | CAM++ fingerprints: different voices scored up to 0.93 | Tested 7 free models; **TitaNet-small**: same voice ≥ 0.72, different ≤ 0.19 |
| Words cut in half / lost at forced cuts | Cut lands mid-word | Last word of a cut segment is carried into the next; repeats removed |
| `with$14 million` | ASR spacing quirk | Text tidy-up |
