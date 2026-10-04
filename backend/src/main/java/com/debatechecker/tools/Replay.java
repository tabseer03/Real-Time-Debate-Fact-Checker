package com.debatechecker.tools;

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
        String[] args = java.util.Arrays.stream(rawArgs).filter(a -> !a.equals("--realtime")).toArray(String[]::new);
        if (args.length < 1) {
            System.err.println("usage: Replay <file.wav> [speakerThreshold] [modelsDir] [--realtime]");
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

        ByteBuffer pcm = readPcm(wav);
        long start = System.nanoTime();
        try (SessionPipeline pipeline = new SessionPipeline("replay", models, threshold, 6,
                s -> System.out.printf("%6.1fs-%5.1fs  [%s]  %s%s%n",
                        s.audioStartSec(), s.audioEndSec(), s.speaker(), s.text(),
                        realtime ? "   (latency " + s.latencyMs() + " ms)" : ""))) {
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
