package com.debatechecker.claims;

import ai.onnxruntime.OrtException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

@Configuration
public class ClaimConfig {

    @Bean(destroyMethod = "close")
    public ClaimGate claimGate(@Value("${debatechecker.claim-gate-dir}") String dir)
            throws IOException, OrtException {
        return new ClaimGate(Path.of(dir));
    }

    @Bean
    public ClaimRewriter claimRewriter(@Value("${debatechecker.llm-url}") String url,
                                       @Value("${debatechecker.llm-model}") String model,
                                       @Value("${debatechecker.llm-timeout-seconds}") int timeoutSeconds) {
        ClaimRewriter rewriter = new ClaimRewriter(url, model, Duration.ofSeconds(timeoutSeconds));
        // Loading the model into the GPU takes several seconds; don't hold up startup for it.
        Thread.ofVirtual().name("llm-warm-up").start(rewriter::warmUp);
        return rewriter;
    }
}
