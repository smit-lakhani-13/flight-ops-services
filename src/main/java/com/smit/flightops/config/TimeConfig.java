package com.smit.flightops.config;

import org.springframework.boot.validation.autoconfigure.ValidationConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * One injectable UTC {@link Clock}, so a test can fix the time instead of asserting a
 * range or sleeping. UTC because every column is {@code TIMESTAMP WITH TIME ZONE} and the
 * pods and laptops run in different zones. Bean Validation's {@code @Future} reads the
 * same Clock, so a pinned test clock moves it too.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /** Hibernate Validator would otherwise ask {@code Clock.systemDefaultZone()}. */
    @Bean
    public ValidationConfigurationCustomizer validationClock(Clock clock) {
        return configuration -> configuration.clockProvider(() -> clock);
    }
}
