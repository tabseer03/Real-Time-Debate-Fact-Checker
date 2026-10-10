package com.debatechecker.tools;

import com.debatechecker.claims.ClaimGate;
import com.debatechecker.claims.ClaimPipeline;
import com.debatechecker.claims.ClaimRewriter;
import com.debatechecker.factcheck.FactChecker;
import com.debatechecker.factcheck.GoogleFactCheck;
import com.debatechecker.factcheck.MinuteDigest;
import com.debatechecker.factcheck.WikipediaEvidence;
import com.debatechecker.speech.Sentence;
import com.debatechecker.speech.SessionPipeline;
import com.debatechecker.speech.SpeechModels;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Runs a recorded session (recordings/session-*.wav, 16 kHz mono 16-bit) through the same
 * speech pipeline as the live server, as fast as the CPU allows. Use it to tune
 * speaker-threshold and segmentation without replaying YouTube.
 *
 * IntelliJ: right-click → Run 'Replay.main()', then Edit Configurations →
 *   Program arguments:  recordings\session-xxxx.wav [speakerThreshold] [modelsDir] [--realtime]
 *   (--realtime feeds audio at playback speed and prints each sentence's latency)
 *   Working directory:  the debate-checker folder (so .\models resolves)
 */
public final class Replay {

    public static void main(String[] rawArgs) throws Exception {
        boolean realtime = java.util.Arrays.asList(rawArgs).contains("--realtime");
        // --title=Some_Video_Title (underscores for spaces): what the extension would send as the tab title
        String title = java.util.Arrays.stream(rawArgs).filter(a -> a.startsWith("--title="))
                .map(a -> a.substring(8).replace('_', ' ')).findFirst().orElse("");
        String[] args = java.util.Arrays.stream(rawArgs).filter(a -> !a.startsWith("--")).toArray(String[]::new);
        if (args.length < 1) {
            System.err.println("usage: Replay <file.wav> [speakerThreshold] [modelsDir] [--realtime] [--title=Video_Title] [--name=S0=Some_Name[,moderator]] [--before=2016-09-26]");
            System.exit(1);
        }
        Path wav = Path.of(args[0]);
        float threshold = args.length > 1 ? Float.parseFloat(args[1]) : 0.45f;
        Path modelsDir = Path.of(args.length > 2 ? args[2] : "models");

        SpeechModels models = new SpeechModels(new SpeechModels.Settings(
                modelsDir.resolve("silero_vad.onnx"),
                modelsDir.resolve("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"),
                modelsDir.resolve("nemo_en_titanet_small.onnx"),
                4, 0.3f, 5f));

        ClaimGate gate = new ClaimGate(modelsDir.resolve("claim-gate"));
        // Same defaults as application.properties. Without Ollama running, sentences are only classified.
        ClaimRewriter llm = new ClaimRewriter("http://127.0.0.1:11434", "gemma3:4b", java.time.Duration.ofSeconds(10));
        // The keys come from the environment, as in application.properties. Passages come from Wikipedia
        // unless --before=2016-09-26 (the day of the recorded debate) is given: a web search without
        // that limit finds articles about the debate itself, and every search is paid for.
        java.time.Duration checkTimeout = java.time.Duration.ofSeconds(8);
        java.time.LocalDate before = java.util.Arrays.stream(rawArgs).filter(a -> a.startsWith("--before="))
                .map(a -> java.time.LocalDate.parse(a.substring(9))).findFirst().orElse(null);
        com.debatechecker.factcheck.ExaEvidence exa = new com.debatechecker.factcheck.ExaEvidence(
                System.getenv().getOrDefault("EXA_API_KEY", ""), checkTimeout, before);
        FactChecker checker = new FactChecker(
                new GoogleFactCheck(System.getenv().getOrDefault("GOOGLE_FACTCHECK_KEY", ""), checkTimeout),
                before != null && exa.enabled() ? exa
                        : new WikipediaEvidence("Podium/0.1 (personal project)", checkTimeout));
        // Once a minute of audio, as in the live server: each speaker's claims and what the check found.
        com.debatechecker.factcheck.GeminiJudge judge = new com.debatechecker.factcheck.GeminiJudge(
                System.getenv().getOrDefault("GEMINI_API_KEY", ""), "gemini-3.5-flash-lite",
                java.time.Duration.ofSeconds(25));
        MinuteDigest digest = new MinuteDigest("replay", checker, judge, before, 40, d -> {
            StringBuilder block = new StringBuilder("\n   DIGEST ");
            for (String line : MinuteDigest.describe(d)) block.append(line).append("\n   ");
            System.out.println(block);
        });
        ClaimPipeline claims = new ClaimPipeline("replay", gate, llm.warmUp() ? llm : null, new ClaimPipeline.Listener() {
            @Override
            public void sentence(Sentence s, ClaimGate.Result verdict) {
                System.out.printf("%6.1fs-%5.1fs  [%s]  %-10s %.2f  %s%s%n",
                        s.audioStartSec(), s.audioEndSec(), s.speaker(), verdict.category(),
                        verdict.factual(), s.text(),
                        realtime ? "   (latency " + s.latencyMs() + " ms)" : "");
                digest.heard(s.audioEndSec());
            }

            @Override
            public void claim(ClaimPipeline.Claim c) {
                System.out.printf("%6.1fs-%5.1fs  [%s]  %-16s %s%s%n",
                        c.sentence().audioStartSec(), c.sentence().audioEndSec(), c.sentence().speaker(),
                        c.rewritten() ? "CLAIM" : "CLAIM (as said)", c.claim(),
                        realtime ? "   (latency " + c.latencyMs() + " ms)" : "");
                digest.add(c);
            }

            @Override
            public void speakers(java.util.Map<String, String> names) {
                System.out.println("                      SPEAKERS         " + names);
            }

            @Override
            public void newSpeaker(String speaker, String said, String guess) {
                System.out.println("                [" + speaker + "]  NEW SPEAKER      " + said);
            }
        });
        claims.setTitle(title);
        // --name=S2=Donald_Trump or --name=S0=Lester_Holt,moderator: what the user would type in the extension
        for (String a : rawArgs) {
            if (!a.startsWith("--name=")) continue;
            String[] kv = a.substring(7).split("=", 2);
            boolean moderator = kv[1].endsWith(",moderator");
            claims.nameSpeaker(kv[0], kv[1].replace(",moderator", "").replace('_', ' '), moderator);
        }

        ByteBuffer pcm = readPcm(wav);
        long start = System.nanoTime();
        try (SessionPipeline pipeline = new SessionPipeline("replay", models, threshold, 6,
                claims::accept)) {
            // Feed in the same 100 ms chunks the extension sends.
            while (pcm.hasRemaining()) {
                int n = Math.min(3200, pcm.remaining());
                ByteBuffer chunk = pcm.slice(pcm.position(), n);
                pipeline.feed(chunk);
                pcm.position(pcm.position() + n);
                if (realtime) {
                    // 3200 bytes = 100 ms, paced like the live extension. Sleep until this audio is
                    // due, not for 100 ms each time: Windows oversleeps by several ms per call,
                    // which fed audio at 0.9x and showed up as latency growing by 15 s over 150 s.
                    long dueNanos = start + pcm.position() * 1_000_000_000L / 32000;
                    long waitMs = (dueNanos - System.nanoTime()) / 1_000_000;
                    if (waitMs > 0) Thread.sleep(waitMs);
                }
            }
        }
        double audioSec = pcm.capacity() / 32000.0;
        double wallSec = (System.nanoTime() - start) / 1e9;
        System.out.printf("%nprocessed %.1fs of audio in %.1fs (%.1fx faster than realtime)%n",
                audioSec, wallSec, audioSec / wallSec);
        claims.close();
        digest.close();
        checker.close();
        gate.close();
        models.close();
    }

    private static ByteBuffer readPcm(Path wav) throws IOException {
        byte[] bytes = Files.readAllBytes(wav);
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        // Walk the RIFF chunks to find "data" rather than assuming a 44-byte header.
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = new String(bytes, pos, 4);
            int size = b.getInt(pos + 4);
            if (id.equals("data")) {
                int len = Math.min(size, bytes.length - pos - 8);
                return ByteBuffer.wrap(bytes, pos + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN);
            }
            pos += 8 + size + (size & 1);
        }
        throw new IOException("no data chunk in " + wav);
    }
}
