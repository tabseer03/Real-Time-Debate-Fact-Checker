package com.debatechecker.speech;

import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.SileroVadModelConfig;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor;
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig;
import com.k2fsa.sherpa.onnx.Vad;
import com.k2fsa.sherpa.onnx.VadModelConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Loads the local speech models once and shares them across sessions.
 *
 * The speech-to-text and speaker-embedding models are big and stateless between calls, so
 * there is one of each. Calls are serialized with locks so two sessions never hit the same
 * native object at once. The VAD keeps per-stream state, so every session gets its own.
 */
public final class SpeechModels implements AutoCloseable {

    public static final int SAMPLE_RATE = 16_000;
    /** Silero VAD expects 512-sample windows at 16 kHz (32 ms). */
    public static final int VAD_WINDOW = 512;

    public record Settings(
            Path vadModel,
            Path asrDir,
            Path speakerModel,
            int asrThreads,
            float vadMinSilenceSeconds,
            float vadMaxSegmentSeconds) {}

    public Settings settings() {
        return settings;
    }

    private final Settings settings;
    private final OfflineRecognizer recognizer;
    private final SpeakerEmbeddingExtractor speakerExtractor;
    private final ReentrantLock asrLock = new ReentrantLock();
    private final ReentrantLock speakerLock = new ReentrantLock();

    public SpeechModels(Settings settings) {
        this.settings = settings;

        Path encoder = settings.asrDir().resolve("encoder.int8.onnx");
        Path decoder = settings.asrDir().resolve("decoder.int8.onnx");
        Path joiner = settings.asrDir().resolve("joiner.int8.onnx");
        Path tokens = settings.asrDir().resolve("tokens.txt");
        requireFiles(settings.vadModel(), encoder, decoder, joiner, tokens, settings.speakerModel());

        // Parakeet TDT is a NeMo transducer: encoder + decoder + joiner.
        OfflineTransducerModelConfig transducer = OfflineTransducerModelConfig.builder()
                .setEncoder(encoder.toString())
                .setDecoder(decoder.toString())
                .setJoiner(joiner.toString())
                .build();
        OfflineModelConfig modelConfig = OfflineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(tokens.toString())
                .setModelType("nemo_transducer")
                .setNumThreads(settings.asrThreads())
                .setDebug(false)
                .setProvider("cpu")
                .build();
        this.recognizer = new OfflineRecognizer(OfflineRecognizerConfig.builder()
                .setOfflineModelConfig(modelConfig)
                .setDecodingMethod("greedy_search")
                .build());

        this.speakerExtractor = new SpeakerEmbeddingExtractor(SpeakerEmbeddingExtractorConfig.builder()
                .setModel(settings.speakerModel().toString())
                .setNumThreads(2)
                .setDebug(false)
                .setProvider("cpu")
                .build());
    }

    /** A fresh voice-activity detector. Each audio stream needs its own (it is stateful). */
    Vad newVad() {
        SileroVadModelConfig silero = SileroVadModelConfig.builder()
                .setModel(settings.vadModel().toString())
                .setThreshold(0.5f)
                .setMinSilenceDuration(settings.vadMinSilenceSeconds())
                .setMinSpeechDuration(0.25f)
                .setMaxSpeechDuration(settings.vadMaxSegmentSeconds())
                .setWindowSize(VAD_WINDOW)
                .build();
        return new Vad(VadModelConfig.builder()
                .setSileroVadModelConfig(silero)
                .setSampleRate(SAMPLE_RATE)
                .setNumThreads(1)
                .setDebug(false)
                .setProvider("cpu")
                .build());
    }

    /**
     * Speech-to-text result. {@code tokens[i]} starts at {@code times[i]} seconds into the
     * segment; a token beginning with a space starts a new word.
     */
    public record Transcript(String text, String[] tokens, float[] times) {}

    /** Speech-to-text for one segment of 16 kHz mono audio in [-1, 1]. */
    public Transcript transcribe(float[] samples) {
        Transcript t = decode(samples, 0);
        // Parakeet occasionally returns nothing for a segment that starts abruptly mid-speech
        // (~1 in 100 cuts in testing). Retrying with silence on both sides recovered all of them;
        // which padding works varies, so try 0.3 s, then 0.6 s.
        if (samples.length >= SAMPLE_RATE) {
            for (float padSec : new float[] {0.3f, 0.6f}) {
                if (!t.text().isEmpty()) break;
                t = decode(samples, (int) (padSec * SAMPLE_RATE));
            }
        }
        return t;
    }

    private Transcript decode(float[] samples, int pad) {
        float[] input = samples;
        if (pad > 0) {
            input = new float[samples.length + 2 * pad];
            System.arraycopy(samples, 0, input, pad, samples.length);
        }
        asrLock.lock();
        try {
            OfflineStream stream = recognizer.createStream();
            try {
                stream.acceptWaveform(input, SAMPLE_RATE);
                recognizer.decode(stream);
                OfflineRecognizerResult r = recognizer.getResult(stream);
                String[] tokens = r.getTokens() == null ? new String[0] : r.getTokens();
                float[] times = r.getTimestamps() == null ? new float[0] : r.getTimestamps().clone();
                float shift = pad / (float) SAMPLE_RATE;
                for (int i = 0; i < times.length; i++) times[i] = Math.max(0f, times[i] - shift);
                return new Transcript(r.getText().trim(), tokens, times);
            } finally {
                stream.release();
            }
        } finally {
            asrLock.unlock();
        }
    }

    /** A "voice fingerprint" for one segment. Similar voices give vectors pointing the same way. */
    public float[] speakerEmbedding(float[] samples) {
        speakerLock.lock();
        try {
            OnlineStream stream = speakerExtractor.createStream();
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE);
                stream.inputFinished();
                return speakerExtractor.compute(stream);
            } finally {
                stream.release();
            }
        } finally {
            speakerLock.unlock();
        }
    }

    @Override
    public void close() {
        recognizer.release();
        speakerExtractor.release();
    }

    private static void requireFiles(Path... files) {
        List<String> missing = new ArrayList<>();
        for (Path f : files) {
            if (!Files.isRegularFile(f)) {
                missing.add(f.toAbsolutePath().toString());
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Speech model files not found:\n  " + String.join("\n  ", missing)
                    + "\nRun download-models.ps1 from the debate-checker folder, and start the backend "
                    + "from that same folder (or change debatechecker.models-dir in application.properties).");
        }
    }
}
