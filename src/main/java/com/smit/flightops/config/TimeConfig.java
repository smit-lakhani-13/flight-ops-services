package com.smit.flightops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * One injectable UTC {@link Clock}, so a test can fix the time instead of asserting a
 * range or sleeping. UTC because every column is {@code TIMESTAMP WITH TIME ZONE} and the
 * pods and laptops run in different zones.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
