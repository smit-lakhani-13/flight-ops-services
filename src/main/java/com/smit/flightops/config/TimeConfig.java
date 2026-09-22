package com.smit.flightops.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * A single injectable {@link Clock}, so nothing on a business path calls
 * {@code Instant.now()} directly.
 *
 * <p>This is not about tidiness. Every {@code Instant.now()} inside a method is
 * a dependency on the wall clock that no test can control, which forces the
 * test to either accept whatever time it gets — and assert something weaker
 * than it meant to — or sleep. Both are how a suite becomes slow and flaky at
 * the same time. With a bean, a test swaps in {@code Clock.fixed(...)} and the
 * assertion becomes an equality rather than a range.
 *
 * <p>{@code systemUTC}, not {@code systemDefaultZone}. The pods run with
 * whatever timezone the base image has, a developer's laptop runs with
 * whatever the laptop has, and a timestamp that means different things in the
 * two places is worse than useless on a money path. Everything persisted here
 * is an {@code Instant} and every column is {@code TIMESTAMP WITH TIME ZONE},
 * so UTC end to end is the only story that stays consistent.
 *
 * <p>{@code Booking.createdAt} still initialises from {@code Instant.now()} in
 * a field initialiser and is the one deliberate exception: a JPA entity is
 * constructed by {@code new}, not by the container, so it cannot be injected
 * without turning every construction site into a factory call. The value is
 * overwritten by nothing and read by nobody before it is persisted, so the
 * cost of that purity would be real and the benefit nil.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
