package com.debatechecker.factcheck;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class FactCheckConfig {

    @Bean(destroyMethod = "close")
    public FactChecker factChecker(@Value("${debatechecker.google-factcheck-key}") String googleKey,
                                   @Value("${debatechecker.exa-key}") String exaKey,
                                   @Value("${debatechecker.wikipedia-user-agent}") String userAgent,
                                   @Value("${debatechecker.factcheck-timeout-seconds}") int timeoutSeconds) {
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        // Live: no date limit on the web search. Without an Exa key, Wikipedia as before.
        ExaEvidence exa = new ExaEvidence(exaKey, timeout, null);
        return new FactChecker(new GoogleFactCheck(googleKey, timeout),
                exa.enabled() ? exa : new WikipediaEvidence(userAgent, timeout));
    }
}
