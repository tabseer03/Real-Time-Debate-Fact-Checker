# Debate Fact Checker — project notes for Claude Code

Chrome extension + Java backend that fact-checks YouTube debates in real time. Each speaker is
handled separately; every sentence is classified FACT_CLAIM / OPINION / JUNK, and claims are
fact-checked. **Target: sentences and claims on screen within 5 s of being spoken; verdicts once
a minute**, as a digest per speaker ("in the last minute Donald Trump said this and this; the
first is confirmed, the second could not be verified"). The user changed this on 2026-10-05 from
"verdict within 5 s": no free checker is both good and that fast.

## Constraints from the user (keep to these)
- **It is for live, current debates** (user, 2026-10-06). The 2012 / 2016 recordings are only test
  material: when replaying one, web evidence must be capped at the day before that debate.
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
- Rewriting check ✅ (2026-10-05): every LLM rewrite is compared with the sentence
  (`claims/ClaimChecker`); a rewrite that fails is replaced by the speaker's own words and the claim
  is sent with `"rewritten": false`. Claims also carry `speakerName`. Verified by Replay on
  session-d5ca3d24 and session-374fafcc; not yet run live.
- Second debate ✅ first look (2026-10-05): session-277ce87c, 7.7 min of the 2012 Obama–Romney town
  hall (Candy Crowley, an audience questioner). Voices separated correctly apart from one bogus voice
  holding two fragments ("Good", "Mr."); the gate passed the candidates' figures; naming needed three
  fixes (see "Names on a second debate"). Only looked at by Replay without typed names.
- **Still open in milestone 3:** debaters' non-claims come out as claims (thanks, "Hillary Clinton
  and Donald Trump are on the same stage tonight."); a rewrite that only rearranges the speaker's
  words can still change the meaning; and "I" inside a story someone is quoting is pinned on the
  speaker (Romney quoting a graduate, "I've got three part-time jobs." → "Romney has three
  part-time jobs." — passes the check because every word was said).
- Milestone 4, part 1 ✅ plumbing, ❌ verdicts (2026-10-05): claims are checked by the minute
  (`factcheck/MinuteDigest`) and sent as {"type":"digest","from","to","speakers":[{speaker, name,
  statements:[{id, claim, sentence, start, verdict, status, rating, sources}]}]}; there is no
  per-claim verdict message any more. A minute closes 10 s of audio after it ends; a claim that
  arrives later goes into the next digest; the rest is sent when the session closes (live, that
  last one is lost, because the extension has closed the socket by then). The verdict comes from a
  published fact-check found through Google's Fact Check Tools API; with none, it is UNVERIFIABLE
  ("could not be verified") with Wikipedia passages. Verified by Replay on session-c51d98d5 (6
  digests, all 31 claims in them) and `MinuteDigestTest`; the handler's `sendDigest` and the
  extension's log lines have not been run live.
- **Result: Google Fact Check gives a real debate almost nothing.** 18-minute recording
  (session-912c6eb1): 135 claims, 0 verdicts; for 127 of them Google returned no fact-check at all,
  and the one near match was a different claim. session-c51d98d5: 31 claims, 0 verdicts. On 16
  claims written by hand in a fact-checker's wording (`eval/claims.txt`): 2 verdicts, both right.
  See "Fact-checking" under Decisions.
- **There is no LLM verdict, on purpose:** gemma3:4b was tried as the judge and failed. So in
  practice every claim is UNVERIFIABLE today.
- **Open in milestone 4, and the thing to decide next:** where verdicts come from. It needs real
  evidence (web search, not word-matched Wikipedia) and a judge better than a 4B model. Gemini
  with search grounding does it well but its free tier allows 20 requests a day (see Decisions);
  the user has been asked to choose. The minute digest changes the sums: one request can carry a
  whole minute's claims (a 90-minute debate = 90 requests, not 600), and there is a minute to
  answer in, so a slow local checker (web search + an entailment model) is possible too.
  **Not built: checking a minute's claims in one call** — `MinuteDigest.check` still asks
  `FactChecker` once per claim.
- Pieces and repeats are left out of the digest ✅ (2026-10-06, `MinuteDigest.notCheckable`): one
  argument arrives as up to nine pieces ("Two and a half trillion.", "There is no leadership."), so
  a claim is not checked if it is under 2 content words, only a figure, unfinished (no . ? ! at the
  end), opens with because / where / which / instead / and (or when / if with no comma), opens with
  an unresolved it / that / they / he, or the same speaker says it more fully in the same minute.
  session-c51d98d5: 31 claims → 18 checked, the 13 dropped all pieces or repeats. Cost: Trump's
  "$5 trillion that we can't bring into our country" argument is all pieces, so none of it is
  checked. Still checked: housekeeping from a moderator not yet known as one, and opinions.
- Web evidence ✅ (2026-10-06, `factcheck/ExaEvidence`): with `EXA_API_KEY` set, the passages come
  from Exa's news search instead of Wikipedia (the server always; `FactCheckTry` always; `Replay`
  only with `--before=2016-09-26`, so that a replay does not spend the allowance by accident). On
  the 16 claims of `eval/claims.txt`, capped at 2016-09-26, nearly every claim got passages that
  bear on it directly (AP on the tax returns, Pew "38.8% ... out of work for 27 weeks or more",
  MarketWatch on the debt nearing $20 trillion, the NYPD's murder count) — Wikipedia managed about
  5 of 16. 1.3–6.4 s a claim, $0.112 for the 16. Not run through Replay or live yet.
- **The judge ✅ first verdicts (2026-10-06, `factcheck/GeminiJudge`):** once a minute
  `MinuteDigest` sends the claims no fact-checker has rated, with their web passages, in one
  request to gemini-3.5-flash-lite (free tier, `GEMINI_API_KEY`, no search tool). A verdict is kept
  only if its quote is found word for word in one of the claim's passages and its TRUE / FALSE
  agrees with its own "agrees" answer; the digest statement then carries `quote` and `reason`, and
  `sources[0]` is the passage quoted. Anything else, a timeout or a 429 leaves "could not be
  verified". Verified by `FactCheckTry` and by Replay with `--before=2016-09-26`; the server boots
  with it; **not run live** (handler's `quote` / `reason` fields, extension log line).
  - 16 claims of `eval/claims.txt` (14 reach the judge), three runs: 9–11 verdicts, 1–2 wrong each
    time and not always the same ones (temperature 0 does not make it repeat). Always wrong: Ford
    moving small-car production -> "Ford is leaving and thousands of jobs are leaving" TRUE. Once:
    "Oil production on federal land is down 14 percent this year" FALSE from another year's figure.
  - session-c51d98d5 by Replay: 18 claims checked, 5 verdicts, 4 good ("Mr. Trump has proposed a
    tax benefit for his family" from The Hill; "Every major party nominee since the late 1970s has
    released tax returns" from USA Today), 1 bad ("People are leaving because taxes are too high"
    confirmed by Americans giving up citizenship — "People" was companies). 2 s a minute.
  - Before two rules were added the same replay gave 10 verdicts, 5 of them poor: housekeeping,
    "trumped up trickle-down" and a prediction were "confirmed" by a passage quoting the speaker
    or describing the debate. The rules: only a statement of fact can be checked; the debate and
    the speaker's own earlier words are not evidence. The judge is told who said each claim.
- **The entailment model is not the judge** (scored on the same passages, see Decisions).
- **Open in the judge:** the free tier's daily limit for flash-lite is unknown (a debate needs
  about 90 requests); nothing shown yet says a verdict is the model's reading and not a
  fact-checker's rating; unresolved claims ("People are leaving ...") get matched to the wrong
  thing; Exa's date limit leaks pages about the debate itself into replays.
- **First full live run in Chrome ✅ plumbing, ❌ verdict quality** (2026-10-07, session-d8551bba,
  5.9 min from the start of the first 2012 Obama–Romney debate, youtube.com/watch?v=KfaBRyCKRhk).
  Everything that had only run by Replay worked live: the page card (shown four times; Moderator
  tick, typed name "Obama", pre-filled guess "Governor Romney" saved), `tabTitle`, rewriting with
  the check and fallback (42 claims, 18 as said), six digests with `quote` / `reason`, the judge
  2.3–3.6 s a minute. Sentences median 2097 / p90 3440 / max 7959 ms, 6 of 89 over 5 s; claims
  median 3149 / p90 4524 / max 9364, 4 of 42 over 5 s. The offscreen console showed the digests
  with quote, source and link (the user's paste); it does not print `reason`.
  - **The debate's date is read from the video, not set by hand** (user, 2026-10-07; added after
    this run, `factcheck/DebateDate`). The extension's start message now carries `description`,
    `published` and `liveNow`, read from YouTube's player in the page's own world
    (`movie_player.getPlayerResponse()`, falling back on the description box and the
    `datePublished` meta tag). The backend takes the first date in the description that follows
    "debate / took place / held / aired / recorded" (else the first date at all, never one later
    than the video), else the upload date; a broadcast on air, or a date within 2 days of today,
    is live and gets no limit. An old date limits that session's web search to pages before it
    (per search now: `EvidenceSource.search(claim, max, before)`, remembered answers are kept apart
    by date) and is the date the judge is told. Answer to the extension: {"type":"debate-date",
    "date","from"}. `DebateDateTest` passes. A re-upload with no date in its description gets the
    re-upload's date, which is too late.
  - **Run again with it, live in Chrome ✅** (session-8d7b9d06, 5.2 min, same video): "DEBATE DATE
    2012-10-03 (from the description)". Same first four minutes, 4 verdicts instead of 6: the two
    from coverage of the debate and the two from later years are gone; "worst financial crisis
    since the Great Depression" now rests on NBC reporting Bernanke (2010). **Left: 3 verdicts
    from the speaker's own side** — whitehouse.gov twice ("Millions of jobs were lost", "The auto
    industry was on the brink of collapse") and the Washington Post reporting Romney telling the
    Dayton story at a rally. "5 million private-sector jobs in 30 months" still gets nothing.
    The moderator stayed on one voice this time (three voices, three people): the run began a few
    seconds later and missed "Gentlemen. Welcome to you both.", so the split in d8551bba comes
    from those opening lines. Seen once: the rewrite dropped "Governor Romney has a perspective
    that" and left "If we cut taxes, skew towards the wealthy ... we will be better off." as
    Obama's own claim (the first run kept it).
  - **7 "confirmed" of 37 statements, none on a sound source** (even "the worst financial crisis
    since the Great Depression" rests on the Obama White House's own blog, dated 2013). Three rest on coverage of this debate or the
    speaker's own telling ("I became the luckiest man on earth because Michelle Obama agreed to
    marry me", "Governor Romney appreciates the welcome ...", the woman in Dayton from Romney's
    rally) — the server treats an old video as live, so Exa has no date limit, and the judge's rule
    against it did not hold. Two rest on the speaker's side talking (Obama's 2009 speech for
    "Millions of jobs were lost", Biden for the auto industry). One uses a 2020 figure ("Small
    business creates the jobs"). The hard figures got nothing: 5 million private-sector jobs in 30
    months, start-ups at a 30-year low, 4 million jobs from energy independence.
  - **The moderator became two voices**: S0 holds only Jim Lehrer's first nine short lines
    (11 s), S1 everything he said after. Only S0 was ticked Moderator, so S1's housekeeping ("Each
    of them has two minutes to start.") was checked as claims. His slow opening also came out in
    pieces ("What are" / "The major differences." / "between" / "the two of you.").
  - The title arrives as "(106) Obama vs. Romney: The first 2012 presidential debate - YouTube"
    (unread counter in front, " - YouTube" behind).
  - The last digest is lost at stop, as expected ("could not send digest").
  - The first backend start of the day timed out the 60 s model warm-up (gemma3:4b cold from disk)
    and logged "Claim rewriting is off"; the server ignores that result and rewrites anyway once
    the model is in memory, so the message is wrong there.
- **Nobody is a witness for themselves** (2026-10-07, `GeminiJudge`, after the three verdicts left
  in session-8d7b9d06). Passages from a politician's own side are not shown to the judge
  (`ownSide`: whitehouse.gov and its archives, house.gov, senate.gov, the parties' sites, any site
  with the speaker's surname in its name); a verdict is dropped if its quote is in the first person
  (I / we / my / our / us / me) or has the speaker saying it ("Romney said", "according to
  Romney"); and the prompt says so. Another person reporting is still fine ("Ben Bernanke told the
  panel ..."). `GeminiJudgeTest.nobodyIsAWitnessForThemselves` holds the three live cases.
  `FactCheckTry eval/claims.txt --before=2016-09-26` afterwards: 8 verdicts of 14 (9–11 before),
  Ford no longer TRUE, tax returns and born-in-Kenya got none this time; still wrong: gasoline
  FALSE from an op-ed's "$1.60". Not caught: the speaker's allies speaking on a news site, and
  "the president noted that ..." when the speaker is the president.
- **Third live run, with that rule ✅** (2026-10-07, session-e9ccf76c, 7.3 min, same video from
  further back). First five minutes: 1 verdict instead of 4 — "Millions of jobs were lost" and the
  Dayton story no longer confirmed; "The auto industry was on the brink of collapse" still is, now
  from NBC reporting Biden (the uncaught kind). Minutes 6–8 gave two good ones: both candidates
  agree the corporate rate is too high (ABC), oil production at its highest since 1997 (CSMonitor,
  2012-09-28). New faults seen:
  - **The moderator split in two again at "Welcome to you both", and the second voice was named
    "Governor Romney" automatically** (Lehrer introduces the candidates on S0, then carries on as
    S1 for 12+ words = "Romney answered"). The card overrode it. Both runs that begin at the
    video's start split there; the recordings d8551bba and e9ccf76c reproduce it.
  - Rewrites that put the wrong person in, and pass the check because known names are allowed:
    "Obama wishes Mr. Romney a happy anniversary ...", "Romeny's husband has had four jobs in
    three years" (the woman Romney was quoting).
  - A typed name cannot be corrected once the card is closed ("Romeny").
- **Why the hard figures get no verdict: the passages are there, the judge declines**
  (2026-10-07, `FactCheckTry` on four claims of that debate, `--before=2012-10-03`). "5 million
  private-sector jobs in 30 months": FactCheck.org and others give 4–4.5 million, so "not enough"
  is defensible. "Four million jobs from energy independence" is a projection (2–3.5 million in
  the passages). But "start-ups are down to a 30-year low" has Reuters on Census data ("the
  startup rate fell to an all-time low ... in 2010") and still gets nothing. A prompt rule saying
  that a stronger statement supports a weaker one changed nothing and was taken out again.
  **The judge also varies run to run on identical passages**: `eval/claims.txt` gave 8 verdicts,
  then 7, sharing only 4 (tax returns, 40% unemployed, born in Kenya, stop-and-frisk each
  appeared in one run and not the other).
- **"Could not be verified" now comes with the closest thing found** (user, 2026-10-07: do not
  just say no; give the viewer a verified text and leave it to their understanding). The judge
  answers a separate field, `closest`, for every claim: the sentence in the passages nearest to
  what the claim is about, even when it settles nothing. It is kept on the same terms as a quote
  (word for word in a passage, own-side pages excluded, never the speaker's own telling; another
  person's "I estimate ..." is allowed here) and arrives as a statement with verdict UNVERIFIABLE
  plus `quote`, `reason` (how it differs) and `sources[0]`. A verdict that code throws away falls
  back on it. Log and extension print `[could not be verified] claim — closest found: "..." —
  source`. Asking for it inside the NOT ENOUGH rule did nothing (the model left the quote empty);
  as its own required field it is filled. `FactCheckTry`: "four million jobs from energy
  independence" -> "creating at least 2 million, and as high as 3.5 million, new jobs" (Oil & Gas
  Journal); gasoline at 1.86 -> "$1.60" as closest instead of the old wrong FALSE; Ford -> the
  small-car move; oil on federal land -> "fell 10 percent" over other years. Weak: "5 million
  private-sector jobs" gets a sentence without the figure, because the figure is in Obama's own
  quoted words. Run live since (next entry). With the text always shown, the judge's run-to-run wavering
  between a verdict and none matters less.
- **"Closest found" run live ✅, and four fixes from what it showed** (2026-10-07, the same video,
  three runs: session-dc70f661 12.8 min from the start, then session-49b34e95 10.8 min and
  session-047a4a12 15.1 min, both begun a few seconds in).
  - **dc70f661, before the fixes:** 59 claims judged, 4 verdicts, 20 with a closest sentence, judge
    2.1–4.5 s. About 9 of the 20 were useful ($4,300 -> Pew's "nearly $3,500"; 100,000 teachers ->
    Obama's stated goal; "small business creates the jobs" -> economists disputing it). 4 were the
    speaker's own words on another page (start-ups at a 30-year low, the $3,600 tax cut, 47
    training programs, Romney's Q&A with AEI); 3 ended mid-word ("... and that it su"); one verdict
    was Romney's own debate line quoted by a blog Exa dates 2012-10-01 ("People in the coal industry
    feel like it's getting crushed by your policies"). "I don't have a $5 trillion tax cut." came
    back as "He does not have ..." and was then left out as an unresolved "he".
  - **Passages end at a sentence** (`ExaEvidence.shortened`): over 500 characters, the cut is at
    the last sentence end, or after the last whole word if there is none in the second half. An
    initial or a title is not a sentence end and a capital or figure must follow — the first
    version cut "the 13 most important U.S. | financial firms".
  - **A rewrite may not open with a pronoun the speaker did not use** (`ClaimChecker.problem`: he /
    she / they / it / that / this / these / those, absent from the sentence): the claim falls back
    on the speaker's words. Fired on "He likes coal." and the $5 trillion denial in both later runs.
  - **The speaker's own words on another page are not evidence** (`GeminiJudge.held`). A quote
    inside quotation marks never gives a verdict, and is not shown as the closest thing either if
    the page mentions the speaker's surname; the same for a first-person quote on such a page or
    on a "Remarks by ..." page; and "Romney said / noted ..." is now looked for in the whole
    sentence the quote is from, not only in the quote (the Dayton quote began after "Romney said
    his heart aches, noting that"). Cost: "Romney said X, but the Labor Department reported Y" is
    dropped whole.
  - **A verdict's quote must be about the claim** (`GeminiJudge.bearsOn`): it shares two of the
    claim's content words (first five letters alike count as the same word), or one if both give a
    figure; and if the claim gives a figure the quote gives one too. Years and lengths of time
    ("four years ago", "a 30-year low") are not figures. A verdict that fails falls back on the
    closest sentence.
  - **49b34e95, with the first two fixes only:** 13 verdicts on 48 claims, 5 of them sound ($5
    trillion tax cut from Forbes, oil production, corporate rate, housing, $4,300 "misleading"
    against Pew). Poor: the Dayton woman "misleading" from Romney's rally, "four million jobs"
    "misleading" from a sentence with no jobs in it, "small business creates the jobs"
    "contradicted" because economists "are challenging the notion", two verdicts on one vague Pew
    sentence. The judge took 8.2, 8.5, 16.2 and 13.0 s in the later minutes (limit 25 s); not seen
    again in the next run, cause unknown.
  - **047a4a12, with all four:** 75 claims judged, 9 verdicts, 14 with a closest sentence, 15
    judge requests of 2.7–5.5 s, no 429. Gone: Dayton, four million jobs, "I also lower
    deductions", the coal line, every closest sentence in the speaker's own words. New and good:
    "47 training programs" (NBC on the GAO report), oil and gas up but on non-federal land
    (Washington Times on a Congress research report). Still poor: "It's energy and trade, the right
    kind of training programs ..." and "Obama believes we should change our tax code ..." confirmed
    (not statements of fact; the digest's unresolved-pronoun rule does not see "It's" as "it");
    "54% of America's workers ..." confirmed from the S Corporation Association's own page (an
    interest group, not on the own-side list). Sentences median 2019 / p90 4479 / max 8380 ms, 21
    of 209 over 5 s; claims 3110 / 5138 / 9231, 12 of 96 over 5 s.
  - **Open after these runs:** the log does not say when a guard drops a verdict, so a guard and
    the judge's own wavering cannot be told apart ("small business creates the jobs" and the $4,300
    claim lost their verdicts in 047a4a12 and no rule should have touched them); non-claims still
    reach the judge ("Governor Romney cites a study.", "X believes / is pleased ..."); the $5
    trillion claim arrived ending in a comma and was skipped as unfinished; "he said" for the
    speaker is not caught; the wrong-person rewrites came back ("Governor Romney's husband has had
    four jobs", "Obama wishes Governor Romney a happy anniversary"); Google Fact Check answered 503
    again. The moderator stayed one voice in both runs that began after "Welcome to you both".
- Next: finish Milestone 4, then 5 (on-page overlay). See bottom.

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
  claims/ClaimChecker        rejects LLM rewrites that add or change things; fallback = as said
  claims/SpeakerNames        who S0/S1/S2 are; NamesCheck runs it over a saved Replay transcript
  factcheck/FactChecker      one for the server: remembered answer → Google → Wikipedia; each lookup
                             on its own virtual thread (network only, no GPU); rating → verdict
  factcheck/GoogleFactCheck  claims:search; which returned review counts as "this claim" (Words)
  factcheck/WikipediaEvidence one request = search + text of 3 pages; picks passages by shared words
  factcheck/ExaEvidence      web search (exa.ai): one paid request per claim, a passage per page;
                             optional "published before" limit for replays (those answers are
                             kept in cache/exa/, gitignored). EvidenceSource = either
  factcheck/GeminiJudge      one request per minute: claims + passages -> verdict, quote, reason;
                             drops a verdict whose quote is not in a passage
  factcheck/FactCheckTry     checks claims from a text file or the command line, no audio
  factcheck/MinuteDigest     per session: a minute's claims → FactChecker → one report by speaker
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
- Fact-check some claims without audio (seconds; `eval/claims.txt` has 16 well-known ones):
  `mvn -f backend/pom.xml -q compile exec:java "-Dexec.mainClass=com.debatechecker.factcheck.FactCheckTry" "-Dexec.args=eval/claims.txt"`
  The Google key is read from the environment variable `GOOGLE_FACTCHECK_KEY` (Replay too).
- `mvn -f backend/pom.xml -q test` runs the fact-check matching tests (no network, no models).
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
- **LLM rewrites are checked in code, and the fallback is the speaker's exact words.** A rewrite is
  rejected if it adds a number or number word, denies something the sentence affirms (or the other
  way round: the first real word after "not/no/never" is compared), drops if / when / unless / maybe /
  probably / would / could / might / should, uses any word that is in neither the sentence, the
  names, nor the 4 context lines, takes more than 4 words from the context lines, or keeps under
  half of the sentence's content words. It compares words, not meaning: strict on purpose (a
  rejected rewrite costs nothing but the name resolution; a wrong claim gets fact-checked as if it
  had been said). Results: d5ca3d24 — 20 of 35 rewrites kept; 374fafcc — 15 of 25. Caught: "650" →
  "$650,000", both roads-and-bridges reversals, "... due to other countries devaluing their
  currencies". Rejected though fine: irregular verbs ("built" vs "building", "made" vs "making")
  and any sentence starting "When ...". On session-277ce87c 22 of 50 were kept, and two bad ones
  got through by only rearranging what was said: "President Romney is a 20-year-old college
  student ..." (the questioner's "President, Governor Romney, as a 20-year-old college student
  ...") and "The President said he should take Detroit bankrupt." Gets through: "New jobs will come from advanced
  manufacturing, innovation, technology, clean renewable energy, and small business." (said: most
  new jobs will come from small business). Latency unchanged (the check is microseconds).
- **Tried and dropped: having the LLM list word substitutions** ("They" → "American jobs") for the
  backend to apply, so no other word could change. gemma3:4b cannot do it: "Hillary Clinton know
  the IRS has made clear the IRS is no prohibition ...", "When you have Hillary Clinton setting up
  the illegal server". Its sentence-kind label (claim / opinion / procedure / fragment) was also
  unreliable ("I have a great company." = fragment), so it is not used as a filter.
- **Speaker names come from addresses, not from the LLM** (deterministic, no GPU time). "Mr.
  Trump?" followed by another voice saying ≥ 12 words = that voice is Trump (12 words because a
  new speaker's first short line often lands on a known voice). Saying a name counts against being
  that person; score = 2 × answered − mentioned; a name needs score ≥ 2 and a lead of 2, and is then
  kept while its score stays ≥ 1 (one stitched-in "Mr. Trump," un-named Trump before that). A voice
  that hands the floor to two different people is "the moderator". An address is a title + surname
  in the first words, the last words, or between commas.
- **Names on a second debate** (session-277ce87c) broke three ways, all fixed: "Mr. President" was
  read as surname "President" (now an office: it means the one person heard as "President X", else
  nothing); "President Barack Obama" gave surname "Barack" (a title may be followed by two names,
  which also gives the full name for display); and the audience member's "Governor Romney, as a
  20-year-old ..." was counted as Romney answering the moderator (whoever says the name in their
  reply is not the addressee). Sentences also arrive out of order across voices, which cancelled a
  pending address: the addresser only "takes the floor back" with a sentence that starts after the
  reply did. A misheard address ("Mr. Romley") then loses to the real name. Result: Mitt Romney at
  126 s, Barack Obama at 256 s, moderator at 405 s; the four Trump–Clinton transcripts unchanged.
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
- **A moderator's sentence is a claim only if P(check-worthy) ≥ 0.5** (user, after the second
  debate: housekeeping was being shown as claims); otherwise it is labelled JUNK. Debaters keep the
  ordinary gate (P(factual) ≥ 0.0506). On the moderators' 32 passed sentences in five transcripts
  this keeps 13 — 11 real ("40% of the unemployed have been unemployed for six months or more",
  "you have not released your tax returns") and 2 housekeeping ("The Gallup organization chose 82
  uncommitted voters ...") — and drops 19, all housekeeping ("I'm Candy Crowley from CNN State of
  the Union.", "You have up to two minutes."). The same threshold on debaters would drop 154 of
  245, so it is moderators only. History: skipping moderators entirely lost the job-growth
  statistics; treating them like debaters showed the housekeeping. Only applies once the voice is
  known to be a moderator (typed on the card, or worked out).
- **Names results** (`claims.NamesCheck`, same debate): 18-min run — Clinton at 25 s, Trump and
  moderator at 159 s; session-c51d98d5 — Trump 16 s, all three 121 s; session-d5ca3d24 — Trump (and
  with the title, Clinton) at 144 s. No wrong name and no change after assignment in any of them.
  Before names are known claims still say "He ..." / "S0 ...": in d5ca3d24 all of Clinton's claims
  come before 144 s. Rewriting with names (longer prompt, 6 examples) adds median 994 / p90 2111 /
  max 2766 ms on d5ca3d24.
- **Fact-checking: the verdict is a published fact-checker's rating, never the local LLM's.**
  gemma3:4b was tried as the judge twice (2026-10-05) and is not reliable enough to show:
  (1) all passages at once, verdict + supporting quote: on Wikipedia passages picked by shared
  words, 4 of 4 wrong ("The United States has 20 trillion dollars in debt." → FALSE from a 1933
  figure; it never answered UNVERIFIABLE); on passages picked by hand, 3 of 8 right — "Obama was
  born in Kenya" + "born in Honolulu" → UNVERIFIABLE, "$19.57 trillion" → "20 trillion" FALSE.
  (2) one passage at a time, SUPPORTS / CONTRADICTS / NEITHER, 5 worked examples, the debate year in
  the prompt: 8 of 12 passages right, ~0.85 s each. Still wrong: "has not released his tax returns"
  against both passages (backwards), a 1933 figure and a 2023 figure taken as contradicting a 2016
  claim. A wrong TRUE / FALSE on screen is worse than none, so it is left out. Nothing bigger fits
  the 4 GB GPU; candidates are a larger model on a free tier or a small entailment model on the CPU.
- **Published ratings become verdicts by keyword** (`FactChecker.verdictOf`): mixed words first
  ("half true", "missing context", one to three Pinocchios → MISLEADING), then false ("mostly false"
  counts as FALSE), then true; anything else UNVERIFIABLE. The rating itself is always sent too.
- **A published review counts only if it is the same claim**: each claim has ≥ 0.6 of the other's
  content words, at least 3 shared, the same figures, and both or neither deny something. Measured
  one way only (on the shorter claim), the real API gave "Donald Trump has not released his tax
  returns." → FALSE from "President Donald Trump is required by law not to show his tax returns",
  and "A 1991 ... booklet identified Barack Obama as having been born in Kenya" (True) was one word
  from making "Barack Obama was born in Kenya." TRUE. Google returns the topic's fact-checks, most
  of them about some narrower or meta claim; both cases are in `FactCheckMatchingTest`. The
  remembered-answers cache uses the figure and denial guards with Jaccard ≥ 0.8 — not embeddings,
  as first planned.
- **Not done: reading a review of the opposite claim backwards.** "Says President Bill Clinton did
  not sign NAFTA." is rated False, which makes "NAFTA was signed by Bill Clinton." true; the denial
  guard just skips it. Telling a clean negation from a different claim by words alone is too risky.
- **Google Fact Check coverage of live debate claims is about zero.** Its index holds what
  fact-checkers chose to write up, in their wording; a debate sentence as spoken (or as rewritten)
  rarely is one. 912c6eb1: 127 of 135 queries came back empty; keyword-only queries did no better
  (14 of 15 empty). The only close match there shows the other hazard: the rewrite "Climate change
  is a hoax perpetrated by the Chinese." (Clinton quoting Trump, attribution lost) against Trump's
  "I do not say that climate change is a hoax ..." rated Mostly False. The lookup is kept because a
  hit is a human verdict with a link, but it cannot be the main source.
- **A small entailment model on the CPU judges better than gemma3:4b and fails safer** (Python
  only, 2026-10-05; the user's idea of a local checker, minus the LLM agent — a small LLM reading
  web pages on a CPU that is busy with speech would take tens of seconds per claim and judge worse
  than gemma). MoritzLaurer/DeBERTa-v3-base-mnli-fever-anli, passage as premise, claim as
  hypothesis, on 17 hand-picked passages: 10 right (both tax-return passages, "40.6% ... jobless
  for 27 weeks or longer" entails "40% ... six months or more"), 4 said neutral where a person
  would say yes ("Clinton signed NAFTA into law", "TPP sets the gold standard"), 2 wrong:
  "$19.57 trillion" contradicts "20 trillion" (no rounding) and a 2023 figure contradicts a 2016
  claim (no dates). 1.9 s per passage in PyTorch on 2 threads — too slow per claim, fine per
  minute; not tried quantised. No free web search has been tried yet to feed it.
- **Entailment model on real search results: 10 of 46 passages confidently wrong** (2026-10-06,
  Python, the same DeBERTa model, the Exa passages for `eval/claims.txt`, judged by hand; a label
  counts at probability >= 0.9). 30 right, 6 "neither" where a person would say yes, 10 wrong.
  The wrong ones are not near misses: a passage that reports someone making the claim is taken as
  the claim (PolitiFact quoting Sanders on Trump and the "hoax", 3 passages, entailment 0.97; "NowThis
  says ... Bill Clinton didn't sign NAFTA" contradicts NAFTA 0.95); the speaker's own rival version
  counts as a contradiction (Trump's "small loan of a million dollars", and the WSJ piece that
  actually reports the larger loans, both 0.97+ against "$14 million"); a different measure
  ("total government debt was $18.6 trillion" against "20 trillion", 0.99); an unrelated transcript
  (0.99 against "fighting ISIS"). Per claim, with "TRUE if a passage entails and none contradicts,
  FALSE the other way round, else nothing": 9 verdicts, 7 right (3 of them resting on a misread
  passage), 2 wrong ("$14 million from his father" FALSE; gasoline at 1.86 FALSE from an op-ed
  saying 1.60), 7 no verdict. It is also slow on these longer passages: median 3.5 s each on 2
  threads, so 3 passages for 7 claims a minute is over a minute of work per minute of debate.
  It reads one sentence against another; who is speaking in the passage is beyond it.
- **Best free judge so far: gemini-3.5-flash-lite reading the Exa passages, no search tool**
  (2026-10-06, Python only, the user's AI Studio key, the same 16 claims and 46 passages; answers
  compared with what the passages allow, judged by hand). It must copy word for word the sentence
  that settles the claim, and code checks the quote is in a passage, else the verdict is dropped
  (this is what stops it answering from memory: without the quote it said "ISIS is a relatively
  recent organisation" and "he actually cut the deficit by more than half" with no passage).
  One claim per request: 14 of 16 acceptable, 2 wrong, median 1.6 s. **All 16 in one request (how a
  minute would be sent): 13 acceptable, 2 wrong verdicts shown, 1 right verdict lost to a misquote,
  6.7 s, 6,700 tokens in.** The wrong ones are evidence about something narrower taken for the
  claim, even with a rule against it: "Murders and rapes were up slightly in July" -> "Murders in
  New York City are up this year" TRUE (both ways of asking); Ford moving small-car production ->
  "Ford is leaving and thousands of jobs are leaving" TRUE (batch only). Prompt and schema are in
  the session scratchpad only: verdicts TRUE / FALSE / MISLEADING / NOT ENOUGH, rules that a
  reported claim, a person's own account, another year's figure and an opinion column settle
  nothing; JSON schema output works when there is no search tool.
  Others on the same test: gemini-2.5-flash 3 wrong and 34 s (and 20 requests a day);
  gemini-3.8-flash timed out at 90 s; gemma-4-31b-it 429, no free quota; gemini-3.5-flash and
  3.6+ 503 or timeouts. About 55 flash-lite requests today without a 429; **its daily limit is
  not known** and a 90-minute debate needs about 90. Small test, old debate: Exa's date limit
  leaks and the model may remember 2016, so live accuracy will be lower.
- **The user's fully local plan (2026-10-05): each minute, each speaker's sentences → gemma3:4b
  cleans and filters the claims → web search on the CPU → gemma judges → digest 10–30 s later.**
  Tried piece by piece in Python, nothing of it is in the backend yet:
  - *Web search without a key does not work.* DuckDuckGo's HTML page gave good results (10 with
    snippets in 0.9–1.3 s, far better than Wikipedia passages: BLS, NPR, the NYT op-ed) for about
    six requests, then HTTP 202 and a bot challenge. Bing answers 200 with unrelated results (MS
    Paint, Forbes billionaires, worse) once it has decided the client is a script. Mojeek and
    Startpage show a captcha, Brave 429. So this needs a search API key (free tiers exist, with
    monthly limits) or it stays on Wikipedia. Not decided.
  - *gemma3:4b over a whole minute is worse than the gate + per-sentence rewrite it would
    replace.* session-c51d98d5: 38 claims from 12 speaker-minutes against 31 now, 0.4–4.5 s per
    call. It does not filter ("Hillary Clinton asked 'Why not?'", "Mr. Trump has a two-minute
    answer.", predictions) and it invents ("Hillary Clinton supports Donald Trump.", "The United
    States is losing two and a half trillion dollars in investment."). It merged one set of
    fragments well ("Donald Trump has proposed a tax benefit for his family." from three lines).
  - *gemma3:4b only for merging, behind the gate, does not work either* (2026-10-06, two prompts,
    the 8 speaker-minutes of session-c51d98d5, 0.4–2.9 s per call). It glues complete claims with
    "and", and it joins pieces into things nobody said using only words that were said, which
    `ClaimChecker` cannot catch: "Two and a half trillion is probably $5 trillion that we can't
    bring into our country", "Mr. Trump has not released his tax returns because nominees have
    released their returns for decades". So pieces are dropped by rule instead (see Status).
  - *gemma judging search results:* only seen on the junk Bing returned, where it rightly said
    "not enough" 12 times of 13; the one mistake is a kind to expect — a result that reports the
    claim being made ("Trump accused Clinton of fighting ISIS her entire adult life") taken as
    support. Not yet measured on good results.
- **Web search: Exa's API, not a self-hosted engine** (2026-10-06). SearXNG only forwards to the
  engines that blocked us, from the same address. Exa's free tier (read from exa.ai/pricing, not
  yet used): $10 of credit that resets every month, no card; search $4 per 1,000, contents $1 per
  1,000 pages per type — so a search with highlights for 5 results is about $0.009, roughly 1,100
  claims a month, 2–4 debates. Check the `costDollars` field of the first real call. Parameters to
  use: `category: "news"`, `contents.highlights`, and **`endPublishedDate` = the day before the
  debate when replaying an old one** — otherwise the search finds articles fact-checking that very
  debate, which a live debate never has. (The Gemini result below was flattered the same way.)
  Tavily (1,000 a month, no card) is the fallback. FRED / BLS for figures are an idea for later.
  **Measured on the first calls:** $0.007 a search (5 results with highlights), so about 1,400
  claims a month. **The date limit needs both ends**: with only `endPublishedDate` Exa returned
  pages it has no date for, 2022 articles among them; with `startPublishedDate` as well every
  result was dated and in range (`ExaEvidence` sends start = 3 years earlier and drops undated
  results). It still leaks a little: Politico pages about the debate itself came back dated
  2016-09-01 (Exa only knows the month). Don't trust a replay verdict that rests on such a page.
- **Google's bulk ClaimReview feed is not the way to preload fact-checks** (2026-10-06;
  storage.googleapis.com/datacommons-feeds/factcheck/latest/data.json, 207 MB, updated daily).
  100,143 fact-checks from 2018 on, 24,000 in English, but US politics has almost stopped arriving:
  PolitiFact 5–11 a month in 2026 and none after July, 221 English items since July (mostly India,
  Sri Lanka, the Philippines). For "Trump", last 30 days: 1 in the feed, 85 from the live API
  (`claims:search` with `maxAgeDays`); last 90 days: 6 against 221 in 3 requests. Those 221 are
  mostly Snopes and Lead Stories on viral posts; 14 are things Trump said. So preloading means
  paging the live API by candidate name, and even that holds few debate-style claims. The feed is
  only good as an archive of 2019–2024 (8,166 PolitiFact items). Nothing built.
- **Seen elsewhere: github.com/scumola/debate** (user's link, 2026-10-06; Python, MIT, one commit,
  no accuracy figures; read, not run). faster-whisper → regex claim patterns → nomic-embed-text +
  FAISS over a local database of published fact-checks (top 5, cosine ≥ 0.75) plus the Google API →
  mistral:7b told to use only the matches, else UNVERIFIED; no web search. Worth taking: **Google's
  whole ClaimReview feed is one free file, no key or quota**
  (https://storage.googleapis.com/datacommons-feeds/factcheck/latest/data.json, about 200 MB), which
  is the way to preload fact-checks before a debate and match them by meaning. Not worth taking:
  the regex gate, and an LLM deciding whether a matched fact-check is the same claim (our
  tax-returns and born-in-Kenya cases are exactly that mistake). Feed not downloaded or tried yet.
- **The judge's label comes last and is asked twice.** With "verdict" first in the JSON, flash-lite
  answered TRUE for "Barack Obama was born in Kenya" beside its own reason "fact-checkers classify
  the claim as false". Now the order is quote, passage, reason, `agrees` (do the quoted words say
  the claim is right), verdict (`propertyOrdering` in the schema), and TRUE with agrees=false or
  FALSE with agrees=true is dropped.
- **Gemini as the judge: good with Google Search grounding, but not on the free tier** (tried
  2026-10-05 with the user's AI Studio key, Python prototypes only — nothing in the backend yet).
  The user's Google AI Pro plan is for the Gemini app and does not change API quotas.
  gemini-2.5-flash + `google_search` tool, thinking off: sensible, sourced answers on all 16 of
  `eval/claims.txt`, median 4.7 s (3.2–6.1). Given the video title it worked out the debate's date
  itself ("On October 16, 2012 ... 40.6% of unemployed Americans had been jobless for 27 weeks or
  longer"), which fixed the two answers the first prompt got wrong for lack of a date; that second
  prompt was right on the 3 claims it reached. Then the quota ran out: **20 requests a day** for
  gemini-2.5-flash (`GenerateRequestsPerDayPerProjectPerModel-FreeTier`), and for every 3.x model a
  grounded request is refused outright (429, no free quota). 2.5-flash-lite and 2.5-pro are closed
  to new users, so 2.5-flash will go too.
  Without search: gemini-3.8-flash answered 3 of 16 (the rest 503 "high demand", and 5–9 s when
  it did answer); gemini-3.5-flash-lite answered 12 of 16 in 1.1–1.9 s (plus three at 11–45 s and
  four empty answers) and was confidently wrong from memory on at least two ("Murders in New York
  City are up this year" → TRUE; oil production on federal land → FALSE, reason made up), with no
  sources to show. With `google_search` the model does not return JSON reliably ("MISLEADING.
  Donald Trump stated ..."): ask for "VERDICT: ... / REASON: ..." lines and parse those.
  A debate makes about 7 claims a minute, so grounded Gemini needs billing switched on.
- **Grounded Gemini cannot replace Exa on this key** (2026-10-07, after the user read that
  2.5-flash-lite allows 500 grounded requests a day). gemini-2.5-flash-lite answers 404 "no longer
  available to new users"; a `google_search` request is refused with 429 by every 3.x model tried
  (3-flash-preview, 3.1-flash-lite, 3.5-flash-lite, 3.5 to 3.8-flash, the -latest aliases). Only
  gemini-2.5-flash searches, 20 requests a day. **And a minute's claims in one grounded request are
  not searched at all**: 16 claims, and again 4 claims, came back with no search queries and no
  sources, answered from memory in 7–8 s, and wrong where memory is wrong ("oil production on
  federal lands increased by 14% in 2011"; "murders in New York City were up by 4.5%"). One claim
  per request does search (3–5 queries, 4–11 s) and gave different verdicts on the same claims.
  Grounded answers also have no passage text, so the quote check cannot be applied, and nothing
  limits the search to before the debate: "Murders ... are up this year" was judged FALSE from the
  full-year 2016 count.
- **Google and Wikipedia are asked at the same time**, since Google nearly always says "nothing"
  and takes 0.8–1.9 s to say it (now and then it hangs: capped at 4 s). A Google hit cancels the
  Wikipedia request.
- **Wikipedia: one request per claim** (`generator=search` + `prop=cirrusdoc`, gzip, 100–700 KB).
  Search-then-fetch-each-page was 4 requests and got 429 "retry after 37 s" after about 15. The
  whole claim is the search query (it does not need every word to match). Passages are pairs of
  neighbouring sentences scored by the claim's words, rarer ones more; reference-list lines are
  dropped (quoted headlines repeat the claim's words best and say nothing); a passage needs half of
  the claim's words, and a claim under 4 content words is not looked up ("... bureaucratic red
  tape." had found the page "Red").
- **Wikipedia passages are related reading, not evidence.** On 16 well-known claims
  (`eval/claims.txt`) about 5 got a passage that bears on the claim (tax returns, the loans from
  Trump's father, NAFTA, stop-and-frisk); figures ("40% of the unemployed ...", gasoline at 1.86)
  and anything "this year" get nothing useful. On session-c51d98d5's own 31 claims, mostly
  arguments rather than facts, none of the first 16 got a passage that settles it.
- **Fact-check numbers** (`--realtime`, session-c51d98d5, Wikipedia only): sentence latency
  unchanged (median 2026 / p90 5635 / max 7605, 10 of 70 over 5 s); claim 3131 / 6825 / 8643, 8 of 31
  over 5 s; verdict 5722 / 9030 / 10693, **22 of 31 over 5 s**; the lookup itself 2193 / 3224 / 5229.
  No 429 in those 31 lookups over 5.5 minutes. With Google as well, in parallel: verdict 5563 / 9171 /
  10655, 20 of 31 over 5 s; lookup 2025 / 2762 / 6068 (that run's sentences were slower, 2277 / 6049 /
  8363, 11 of 70 — the machine had just run other tests).
- **The Wikipedia request is timed as a whole** (`sendAsync(...).get(timeout)`): HttpRequest's own
  timeout stops at the headers, and one body took 40 s to arrive.
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
- **Wikipedia limits requests from this connection**: roughly 10–15 a minute, then 429 with
  `retry-after` 13–37 s. Hit after 10 lookups in 40 s and after 14 in 50 s, both with 1.5 s pauses.
  `WikipediaEvidence` then makes no request until the time is up; those claims get no passages and
  are not remembered. Leave pauses when testing with `FactCheckTry`.
- The Google key lives only in IntelliJ's run configuration (`.idea/workspace.xml`, gitignored), as
  an environment variable. On 2026-10-05 its *name* had been typed with quotes
  (`"GOOGLE_FACTCHECK_KEY"`), so the backend did not see it; check for "No Google Fact Check key" in
  the log. A terminal run needs `$env:GOOGLE_FACTCHECK_KEY` set by hand. Never print the key.
- `mvn` is not on PATH. IntelliJ's is at
  `C:\Program Files\JetBrains\IntelliJ IDEA 2024.2.0.1\plugins\maven\lib\maven3\bin\mvn.cmd`.
- After Ollama starts, the first call loads the model from disk (over 10 s): the warm-up call has
  its own 60 s timeout since 2026-10-06; before that such a Replay ran with rewriting off.
- The keys can be read from `.idea/workspace.xml` into `$env:` for a terminal run without showing
  them (regex on `name="GOOGLE_FACTCHECK_KEY" value="..."`, same for `EXA_API_KEY` and
  `GEMINI_API_KEY`).
- Google's Fact Check API answered 503 "service is currently unavailable" to most requests on the
  evening of 2026-10-06; the check carries on without it (the answer is then not remembered).
- Don't call Ollama while a Replay is starting: its warm-up timed out behind another request and
  that run had rewriting off.
- The extension needs the `activeTab` permission, or `tab.url` is undefined. `scripting` +
  `activeTab` is what lets it inject overlay.js; the offscreen document has no `chrome.tabs`, so
  messages for the page go through the service worker.
- overlay.js must not use `innerHTML`: YouTube enforces Trusted Types.
- Capturing a tab mutes it; offscreen.js reconnects the source to `audioCtx.destination`.
- `ConcurrentWebSocketSessionDecorator` is required: sentences are sent from several speaker threads.
- "could not send sentence … session has been closed" at stop is harmless (last sentences flush
  after the extension closes the socket).

## Next milestones
4. **Fact-checking (free), started.** Done: Google Fact Check lookup (works, finds almost nothing),
   Wikipedia passages, remembered answers, verdict messages. To do: a judge for claims with no
   published fact-check, which is nearly all of them (not gemma3:4b); better evidence than
   word-matched Wikipedia (figures need
   a source like BLS / FRED, or web search through self-hosted SearXNG); the claim's date ("this
   year" in a 2012 video). "Checking…" needs no message: a claim is unchecked until the verdict with
   its `id` arrives, and one always does.
5. **Overlay (planned, not started):** content script on youtube.com showing speaker, claim, verdict, sources, timestamp.
