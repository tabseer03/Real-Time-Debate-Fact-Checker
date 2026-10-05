package com.debatechecker.claims;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.k2fsa.sherpa.onnx.LibraryUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Decides which sentences are worth sending to the LLM for rewriting and fact-checking.
 *
 * A small transformer fine-tuned on ClaimBuster (see classifier/) scores each sentence as
 * non-factual / unimportant factual / check-worthy factual. A sentence passes when
 * P(unimportant factual) + P(check-worthy) reaches the threshold in gate.json, which was chosen
 * to keep 95% of check-worthy sentences. A few milliseconds on CPU, so it runs on every sentence.
 */
public final class ClaimGate implements AutoCloseable {

    public enum Category { FACT_CLAIM, OPINION, JUNK }

    /**
     * @param factual      P(unimportant factual) + P(check-worthy), 0..1
     * @param checkWorthy  P(check-worthy) alone
     */
    public record Result(Category category, double factual, double checkWorthy) {}

    /** Under this many words ("Thank you.", "Mr. Trump.") a blocked sentence is junk, not an opinion. */
    private static final int MIN_OPINION_WORDS = 3;

    static {
        // Run on the onnxruntime.dll that sherpa-onnx ships, not the one in Microsoft's jar. That
        // one fails to initialise here ("DLL initialization routine failed"): it needs a newer
        // Visual C++ runtime than the msvcp140.dll in the JDK's bin folder, which the JVM has
        // already loaded. sherpa's is loaded first, so the JNI bridge binds to it by name; it also
        // means one runtime in the process instead of two.
        LibraryUtils.load();
        System.setProperty("onnxruntime.native.onnxruntime.skip", "true");
    }

    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final WordPieceTokenizer tokenizer;
    private final double threshold;
    private final int maxLength;

    public ClaimGate(Path dir) throws IOException, OrtException {
        Path model = dir.resolve("gate.onnx");
        Path vocab = dir.resolve("vocab.txt");
        Path config = dir.resolve("gate.json");
        for (Path f : new Path[] {model, vocab, config}) {
            if (!Files.isRegularFile(f)) {
                throw new IllegalStateException("Claim gate file not found: " + f.toAbsolutePath()
                        + "\nBuild it with classifier/finetune.py then classifier/export_onnx.py, "
                        + "run from the debate-checker folder.");
            }
        }
        JsonNode json = new ObjectMapper().readTree(config.toFile());
        this.threshold = json.get("threshold").asDouble();
        this.maxLength = json.get("max_length").asInt();
        this.tokenizer = new WordPieceTokenizer(vocab);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            // One sentence at a time on a tiny model: more threads only take cores from speech-to-text.
            options.setIntraOpNumThreads(1);
            this.session = env.createSession(model.toString(), options);
        }
    }

    public double threshold() {
        return threshold;
    }

    long[] tokenIds(String sentence) {
        return tokenizer.encode(sentence, maxLength);
    }

    /** Safe to call from several speaker threads at once. */
    public Result classify(String sentence) {
        long[] ids = tokenIds(sentence);
        long[] ones = new long[ids.length];
        java.util.Arrays.fill(ones, 1);
        try (OnnxTensor inputIds = OnnxTensor.createTensor(env, new long[][] {ids});
             OnnxTensor mask = OnnxTensor.createTensor(env, new long[][] {ones});
             OnnxTensor types = OnnxTensor.createTensor(env, new long[][] {new long[ids.length]});
             OrtSession.Result out = session.run(Map.of(
                     "input_ids", inputIds, "attention_mask", mask, "token_type_ids", types))) {
            float[] logits = ((float[][]) out.get(0).getValue())[0];
            double max = Math.max(logits[0], Math.max(logits[1], logits[2]));
            double nfs = Math.exp(logits[0] - max);
            double ufs = Math.exp(logits[1] - max);
            double cfs = Math.exp(logits[2] - max);
            double sum = nfs + ufs + cfs;
            double factual = (ufs + cfs) / sum;
            Category category = factual >= threshold ? Category.FACT_CLAIM
                    : sentence.trim().split("\\s+").length < MIN_OPINION_WORDS ? Category.JUNK
                    : Category.OPINION;
            return new Result(category, factual, cfs / sum);
        } catch (OrtException e) {
            throw new IllegalStateException("claim gate failed on: " + sentence, e);
        }
    }

    @Override
    public void close() throws OrtException {
        session.close();
    }
}
