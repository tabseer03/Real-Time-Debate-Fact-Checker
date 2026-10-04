package com.debatechecker.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Per-connection throughput stats and a .wav recording of what was captured (for debugging). */
class AudioSession {

    private static final Logger log = LoggerFactory.getLogger(AudioSession.class);
    private static final int SAMPLE_RATE = 16_000;
    private static final int BYTES_PER_SECOND = SAMPLE_RATE * 2; // 16-bit mono
    private static final int WAV_HEADER_SIZE = 44;
    private static final int STATS_EVERY_SECONDS = 10;

    private final String id;
    private final Path recording;
    private final FileChannel out;

    private long totalBytes = 0;
    private long windowBytes = 0;
    private long windowStart = System.nanoTime();

    AudioSession(String id, String recordingsDir) throws IOException {
        this.id = id;
        Path dir = Path.of(recordingsDir);
        Files.createDirectories(dir);
        this.recording = dir.resolve("session-" + id + ".wav");
        this.out = FileChannel.open(recording,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
        out.position(WAV_HEADER_SIZE); // header is written on close, once the length is known
    }

    void onChunk(ByteBuffer pcm) throws IOException {
        int len = pcm.remaining();
        out.write(pcm);
        totalBytes += len;
        windowBytes += len;

        long now = System.nanoTime();
        double elapsed = (now - windowStart) / 1e9;
        if (elapsed >= STATS_EVERY_SECONDS) {
            double realtime = (windowBytes / (double) BYTES_PER_SECOND) / elapsed;
            log.debug("[{}] audio {}x realtime, total {}s", id,
                    String.format("%.2f", realtime), String.format("%.1f", totalBytes / (double) BYTES_PER_SECOND));
            if (realtime < 0.9) {
                log.warn("[{}] audio arriving at only {}x realtime — is the tab in the background?",
                        id, String.format("%.2f", realtime));
            }
            windowStart = now;
            windowBytes = 0;
        }
    }

    void close() throws IOException {
        out.write(wavHeader(totalBytes), 0);
        out.close();
        log.info("[{}] saved {}s of audio to {}", id,
                String.format("%.1f", totalBytes / (double) BYTES_PER_SECOND), recording.toAbsolutePath());
    }

    private static ByteBuffer wavHeader(long dataLen) {
        ByteBuffer h = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt((int) (36 + dataLen)).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16)
         .putShort((short) 1)             // PCM
         .putShort((short) 1)             // mono
         .putInt(SAMPLE_RATE)
         .putInt(BYTES_PER_SECOND)
         .putShort((short) 2)             // block align
         .putShort((short) 16);           // bits per sample
        h.put("data".getBytes()).putInt((int) dataLen);
        return h.flip();
    }
}
