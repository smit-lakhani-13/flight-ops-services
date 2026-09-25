package com.smit.flightops.support;

import com.smit.flightops.observability.BookingMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Gives a {@code @WebMvcTest} slice the real {@link BookingMetrics} on an in-memory
 * registry, since a slice has no metrics auto-configuration and
 * {@code GlobalExceptionHandler} takes the bean in its constructor.
 */
@TestConfiguration
public class MetricsTestConfig {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    BookingMetrics bookingMetrics(MeterRegistry registry) {
        return new BookingMetrics(registry);
    }
}
