package com.xammer.cloud.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable {@link Clock} so time-dependent onboarding logic (token TTLs, the
 * disable-automation cut-off, verification freshness) is testable with a fixed clock
 * (design §3.3). {@link ConditionalOnMissingBean} lets a test supply its own.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
