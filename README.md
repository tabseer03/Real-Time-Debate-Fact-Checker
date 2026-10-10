# Podium: Real-Time Debate Fact Checker

Podium is a Chrome extension and a Java backend that listen to a debate on YouTube while it plays, work out
who is speaking, pick out the factual claims, and check them against the web. Results appear in a
panel on the YouTube page: each claim, who made it, and a verdict with the sentence it rests on and
a link to the source.

It is built for live debates and costs nothing to run: the speech models and the rewriting model
run on your own machine, and the web search and the judge use free tiers.

It has been run in Chrome on five US presidential debates (2008, 2012 and 2016), 223 minutes in
all; the measurements are under [Results](#results). Every verdict is an automated reading of a
source, shown with that source so the viewer can judge it.

## What you see

Click the extension icon on a YouTube debate and a panel opens at the top right of the page:

- **The sentence being spoken**, about 2 seconds behind the video.
- **A chip per voice.** When a new voice has said a few words, a card asks who it is; type the
  name and tick *Moderator* if it is one. Click a chip later to correct a name.
- **A card per claim**, newest first: speaker, time in the video (click to jump there), the claim,
  and the speaker's own words if the claim was reworded.
- **A verdict on each card**, every 40 seconds: *confirmed*, *contradicted*, *misleading* or *could
  not be verified*, with the quoted sentence from the source, one line of reasoning and the link.
  When nothing settles a claim, the card shows the closest thing found, for you to compare.
- **Opinions and non-claims** are greyed out rather than checked.

Every verdict is labelled *Automated reading of this source, not a fact-checker's rating*, unless
it comes from a published fact-check, which is shown with the fact-checker's own rating.

## How it works

```
YouTube tab ─▶ extension (16 kHz mono PCM, 100 ms chunks) ─▶ WebSocket ─▶ Spring Boot backend

 speech/    SessionPipeline (one virtual thread per tab)
              SpeechSegmenter ── Silero speech probability; cut at 0.3 s pauses, hard cap 5 s
              TitaNet fingerprints on 1 s windows every 0.25 s ── split where the voice changes
              Parakeet TDT 0.6B ── speech-to-text with word timestamps
              SpeakerTracker ── "same voice as S0?" (cosine ≥ 0.45), else a new speaker
            SpeakerWorker (one virtual thread per speaker) ── stitches parts into sentences

 claims/    ClaimPipeline (per tab)
              ClaimGate ── fine-tuned MiniLM classifier: is this sentence factual at all? (7 ms)
              NonClaims ── courtesies, feelings and quoted stories are not claims
              up to three of a speaker's sentences are taken together as one claim, and the
                parts that add nothing are trimmed
              SpeakerNames ── who S0, S1, ... are (typed on the page, else worked out)
              ClaimRewriter ── gemma3:4b in Ollama rewrites the claim to stand alone (GPU)
              ClaimChecker ── rejects rewrites that add or change things; the fallback is the
                              speaker's own words

 factcheck/ MinuteDigest (per tab) ── every 40 s, the claims of that window, speaker by speaker
              FactChecker ── a published fact-check of the same claim (Google Fact Check Tools)
              ExaEvidence ── otherwise: passages from a web news search (Wikipedia without a key)
              GeminiJudge ── one request per window: fact or opinion? what do the passages say?
                             a verdict is kept only if its quote is word for word in a passage
              DebateDate ── for an old video, the debate's date is read from the page and the
                            search is limited to what was published before it

 ─▶ JSON back to the extension ─▶ panel on the YouTube page
```

Speech models run on the CPU and the rewriting model entirely on the GPU, so the two do not
compete. Fact-checking is network calls only.

Guards on the judge, all in code: a politician's own side is not a witness for their claim (their
own site, their own quoted words, "Romney said ..."); a quote must be about the claim and carry a
figure if the claim does; the judge's verdict must agree with its own answer to "do these words
say the claim is right?".

## Requirements

- Windows 10/11, x64. (The speech library's native files in `backend/pom.xml` are the Windows
  ones; the comment there names the Linux and macOS equivalents. Only Windows has been run.)
- JDK 21 or newer, and Maven (IntelliJ's bundled Maven is enough).
- Chrome.
- [Ollama](https://ollama.com), and an NVIDIA GPU with 4 GB of memory for `gemma3:4b`.
- Python 3.12, only to build the claim classifier once.
- Three free API keys (step 4).

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
(CC BY 4.0), about 23,000 sentences from US presidential debates. It is not downloadable
ready-made, so build it once:

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

### 3. Rewriting model

Install Ollama, keep it running (the llama icon in the system tray), and fetch the model once:

```powershell
ollama pull gemma3:4b
```

Without Ollama the backend still starts and classifies sentences, but sends no claims.

### 4. Keys

All three are free. Give them to the backend as environment variables (IntelliJ: the run
configuration's *Environment variables*, names typed without quotes; PowerShell:
`$env:NAME = "..."`). Never put them in a file that is committed.

| Variable | What it is for | Where to get it | Without it |
|---|---|---|---|
| `EXA_API_KEY` | Web search for passages that bear on a claim | [exa.ai](https://exa.ai): $10 of credit a month, no card; about $0.007 a claim | Wikipedia passages, which rarely settle anything |
| `GEMINI_API_KEY` | The judge that reads the passages (`gemini-3.5-flash-lite`, free tier) | [aistudio.google.com](https://aistudio.google.com) → *Get API key* | every claim is "could not be verified" |
| `GOOGLE_FACTCHECK_KEY` | Published fact-checks of the same claim | [console.cloud.google.com](https://console.cloud.google.com) → enable **Fact Check Tools API** → *Credentials* → *API key* | no fact-checkers' ratings (they are rare for live speech anyway) |

### 5. Backend

In IntelliJ, run `DebateCheckerApplication` with the working directory set to the project folder.
Or from a terminal, with the full path to the project folder:

```powershell
mvn -f backend/pom.xml spring-boot:run "-Dspring-boot.run.workingDirectory=C:\path\to\project"
```

It is ready when the log shows `Started DebateCheckerApplication`. The log is also written to
`logs\backend.log`.

### 6. Extension

`chrome://extensions` → turn on *Developer mode* → *Load unpacked* → choose the `extension` folder.
Reload it there after any change to its files.

## Using it

1. Open a debate on YouTube and click the extension icon. The badge shows `ON` and the panel opens.
2. Name each voice when its card appears (or press *Skip*: names are then worked out from how
   people are addressed, which is less reliable). Tick *Moderator* for moderators, so their
   housekeeping is not checked as claims.
3. Read the cards. A claim shows *checking…* until the digest for its 40 seconds arrives.
4. Click the icon again to stop. The panel stays so you can read it.

For an old video the backend reads the debate's date from the page and only uses web pages
published before it, so a replay is checked as it would have been on the night.

The same output is in the extension's console (`chrome://extensions` → this extension →
*Inspect views: offscreen.html*) and in the backend log.

### Messages

The backend sends these over the WebSocket (`ws://localhost:8080/audio`):

| `type` | Fields | When |
|---|---|---|
| `sentence` | `speaker`, `text`, `start`, `end`, `latencyMs`, `category` (`FACT_CLAIM` / `OPINION` / `JUNK`), `factual` | every finished sentence |
| `claim` | `id`, `speaker`, `speakerName`, `claim`, `rewritten`, `sentence`, `start`, `end`, `latencyMs` | each claim; sent again under the same `id` when the speaker's next sentence is added to it |
| `digest` | `from`, `to` (seconds), `judgeFailed`, `speakers`: each `speaker`, `name`, `statements`; each statement `id` (the claim's), `claim`, `sentence`, `start`, `verdict` (`TRUE` / `FALSE` / `MISLEADING` / `UNVERIFIABLE`), `status`, `kind` (`fact` / `opinion` / `none`), `quote`, `reason`, `rating` (a fact-checker's own words, or `""`), `sources` (each `source`, `title`, `url`, `text`) | every 40 s of the debate that had claims, about 10 s after the window ends plus the time to search and judge |
| `speakers` | `names` (label → name) | whenever a name is learned or typed |
| `new-speaker` | `speaker`, `text`, `guess` | a voice not heard before has said 6 words |
| `debate-date` | `date`, `from` | the debate's date was read from the video |

The extension answers a `new-speaker` message with
`{"type":"speaker-name","speaker":"S2","name":"Donald Trump","moderator":false}`.

## Replay: tune without YouTube

Every live session is saved to `recordings\session-<id>.wav`. Run one back through the same
pipeline, as fast as the machine allows (about twice real time):

```powershell
mvn -f backend/pom.xml -q compile exec:java "-Dexec.mainClass=com.debatechecker.tools.Replay" "-Dexec.args=recordings/session-XXXX.wav 0.45"
```

Add to the arguments:

- `--realtime` to feed the audio at playback speed and print each line's latency.
- `--name=S2=Donald_Trump` or `--name=S0=Lester_Holt,moderator` to name a voice as you would on the
  card (underscores for spaces).
- `--title=Some_Video_Title` to pass the tab title.
- `--before=2016-09-26` to check the claims against the web as it was before that day. This spends
  the search allowance; without it a replay uses no web search.

Other tools:

- `factcheck.FactCheckTry eval/claims.txt` checks claims from a text file, no audio (seconds).
- `claims.GateCheck` compares the Java classifier with the Python one.
- `claims.NamesCheck` runs the speaker naming over a saved Replay transcript.
- `mvn -f backend/pom.xml -q test` runs the unit tests (no network, no models).

## Results

Measured on 25 recordings of five debates (2008 McCain–Obama, 2012 Obama–Romney twice, 2016
Trump–Clinton twice), 223 minutes in all.

| | Result | Notes |
|---|---|---|
| Sentence delay | median about 2 s | 9 in 10 within 5 s; the slower ones are at speaker changes and crosstalk |
| Claim delay | median about 3 s | rewriting adds about 1 s |
| Verdict delay | 40 s window + 10 s + search and judge | the judge answers in 2–5 s, up to 25 s when its service is busy |
| Speakers | stable over 18 minutes, no voice invented by audience noise | |
| Claim classifier | keeps 94% of check-worthy sentences, passes 57% of all sentences | held-out 2016 debates from ClaimBuster |
| Cards | about 3.7 a minute, from 13 sentences a minute | 823 cards from 3,012 sentences |
| Claims shown reworded | about 45% | the rest are the speaker's own words, trimmed |
| Verdicts | about 10 on 55 cards in 16 minutes | 2008 McCain–Obama debate |

Verdicts from those runs: a $52.5 billion economic plan (confirmed, NBC), a record $455 billion
deficit (confirmed), "second highest business tax rate ... Ireland, it's 11%" (misleading:
Ireland's rate was 12.5%), oil production at its highest since 1997 (confirmed).

## Scope and limitations

- **Verdicts are automated readings.** The judge reads what the search found and can be wrong;
  each card therefore shows the quoted sentence and its source, and a claim nothing settles is
  shown with the closest text found instead of a verdict.
- **Free tiers set the capacity.** The search credit covers two to four full debates a month, and
  the judge is a single free model: when its service is busy, a 40-second window shows *check
  unavailable*.
- **Names are best typed on the card.** For skipped voices they are worked out from how people
  are addressed, which is less reliable, and a moderator should be ticked so that housekeeping
  is not checked.
- **A claim is reworded only when the rewrite passes a strict check**; otherwise the card shows
  the speaker's own words.
- **Crosstalk.** Overlapping speech gives fragments, and a line under about a second ("No.",
  "Mr. Trump.") goes to whoever is speaking around it.
- **English**, US presidential debates, Windows.

The reasons behind the design choices, with the measurements from every run, are in
[CLAUDE.md](CLAUDE.md).

## Tuning (`backend/src/main/resources/application.properties`)

| What you see | Change |
|---|---|
| One person split into S0, S2, S3… | lower `speaker-threshold` (0.4, 0.35) |
| Two people merged into one label | raise `speaker-threshold` (0.5, 0.55) |
| `asr` times over ~1500 ms in the log | raise `asr-threads` to 6 |
| Sentences chopped mid-thought | raise `vad-min-silence-seconds` to 0.4 |
| Verdicts sooner or later | `digest-seconds` (40; one judge request per window) |
| A different rewriting model | `llm-model` (and `ollama pull` it) |
| A different judge | `judge-model` |

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
