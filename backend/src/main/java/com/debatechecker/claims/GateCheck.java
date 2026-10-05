package com.debatechecker.claims;

import com.debatechecker.speech.SpeechModels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Checks the Java claim gate against what Python produced for the same sentences
 * (classifier/data/parity.tsv, written by classifier/export_onnx.py): same token ids, same score.
 * The speech models are loaded first and used afterwards, because both they and the gate bring
 * an onnxruntime.dll into the JVM and this is where a clash would show.
 *
 *   mvn -f backend/pom.xml -q compile exec:java "-Dexec.mainClass=com.debatechecker.claims.GateCheck"
 *   Working directory: the debate-checker folder. Add "gate-first" to load the gate before speech.
 */
public final class GateCheck {

    public static void main(String[] args) throws Exception {
        boolean gateFirst = args.length > 0 && args[0].equals("gate-first");
        Path modelsDir = Path.of("models");
        ClaimGate gate = gateFirst ? new ClaimGate(modelsDir.resolve("claim-gate")) : null;
        SpeechModels speech = new SpeechModels(new SpeechModels.Settings(
                modelsDir.resolve("silero_vad.onnx"),
                modelsDir.resolve("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8"),
                modelsDir.resolve("nemo_en_titanet_small.onnx"),
                4, 0.3f, 5f));
        if (gate == null) gate = new ClaimGate(modelsDir.resolve("claim-gate"));
        System.out.println("loaded " + (gateFirst ? "gate, then speech models" : "speech models, then gate"));

        List<String> lines = Files.readAllLines(Path.of("classifier/data/parity.tsv"));
        int idMismatch = 0, passed = 0, cfs = 0, cfsPassed = 0, gateFlips = 0;
        double worst = 0;
        long start = System.nanoTime();
        for (String line : lines) {
            String[] f = line.split("\t", 4);
            int label = Integer.parseInt(f[0]);
            double want = Double.parseDouble(f[1]);
            String text = f[3];

            StringBuilder ids = new StringBuilder();
            for (long id : gate.tokenIds(text)) ids.append(ids.isEmpty() ? "" : " ").append(id);
            if (!ids.toString().equals(f[2])) {
                if (idMismatch++ < 5) System.out.println("token ids differ: " + text);
            }
            ClaimGate.Result r = gate.classify(text);
            worst = Math.max(worst, Math.abs(r.factual() - want));
            boolean pass = r.category() == ClaimGate.Category.FACT_CLAIM;
            if (pass != (want >= gate.threshold())) gateFlips++;
            if (pass) passed++;
            if (label == 2) {
                cfs++;
                if (pass) cfsPassed++;
            }
        }
        double ms = (System.nanoTime() - start) / 1e6 / lines.size();
        System.out.printf("%d sentences: %d with different token ids, largest score difference %.6f, "
                + "%d gate decisions differ%n", lines.size(), idMismatch, worst, gateFlips);
        System.out.printf("gate at threshold %.4f: passes %.1f%%, keeps %.1f%% of check-worthy; %.1f ms per sentence%n",
                gate.threshold(), 100.0 * passed / lines.size(), 100.0 * cfsPassed / cfs, ms);

        // Both runtimes still work side by side after the gate has run.
        float[] silence = new float[SpeechModels.SAMPLE_RATE * 2];
        System.out.println("speech models after gate: transcript \"" + speech.transcribe(silence).text()
                + "\", embedding of " + speech.speakerEmbedding(silence).length + " values");
        gate.close();
        speech.close();
    }
}
