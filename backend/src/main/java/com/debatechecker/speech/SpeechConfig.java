package com.debatechecker.speech;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class SpeechConfig {

    private static final Logger log = LoggerFactory.getLogger(SpeechConfig.class);

    @Bean(destroyMethod = "close")
    public SpeechModels speechModels(
            @Value("${debatechecker.vad-model}") String vadModel,
            @Value("${debatechecker.asr-dir}") String asrDir,
            @Value("${debatechecker.speaker-model}") String speakerModel,
            @Value("${debatechecker.asr-threads}") int asrThreads,
            @Value("${debatechecker.vad-min-silence-seconds}") float minSilence,
            @Value("${debatechecker.vad-max-segment-seconds}") float maxSegment) {

        long start = System.nanoTime();
        SpeechModels models = new SpeechModels(new SpeechModels.Settings(
                Path.of(vadModel), Path.of(asrDir), Path.of(speakerModel),
                asrThreads, minSilence, maxSegment));
        log.info("Speech models loaded in {} ms", (System.nanoTime() - start) / 1_000_000);
        return models;
    }
}
