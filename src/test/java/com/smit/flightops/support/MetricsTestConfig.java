package com.smit.flightops.support;

import com.smit.flightops.observability.BookingMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Gives a {@code @WebMvcTest} slice the real {@link BookingMetrics} on top of an
 * in-memory registry.
 *
 * <p>A slice loads the controller, the {@code @RestControllerAdvice} and very
 * little else — in particular no metrics auto-configuration, so there is no
 * {@link MeterRegistry} for {@code BookingMetrics} to be built from and the
 * advice fails to construct. The obvious fix is a mock, and it is the wrong
 * one: a mocked counter proves the handler called a method, not that the meter
 * it claims to publish actually exists under the name and tag the alert will
 * query. With a {@link SimpleMeterRegistry} the test can read the count back
 * out by name, which is the assertion worth having.
 *
 * <p>Boot's {@code @AutoConfigureObservability} would also work and does far
 * more: it switches on the whole metrics and tracing stack for the slice. Six
 * lines that supply exactly the one bean needed is easier to reason about than
 * an annotation whose blast radius is a version-dependent list of
 * auto-configurations.
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
