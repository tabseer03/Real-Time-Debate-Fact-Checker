package com.debatechecker.factcheck;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class FactCheckConfig {

    @Bean(destroyMethod = "close")
    public FactChecker factChecker(@Value("${debatechecker.google-factcheck-key}") String googleKey,
                                   @Value("${debatechecker.wikipedia-user-agent}") String userAgent,
                                   @Value("${debatechecker.factcheck-timeout-seconds}") int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        return new FactChecker(new GoogleFactCheck(googleKey, timeout), new WikipediaEvidence(userAgent, timeout));
    }
}
