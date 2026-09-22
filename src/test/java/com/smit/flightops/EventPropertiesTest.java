package com.smit.flightops;

import com.smit.flightops.config.EventProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The event transport mode refusing an unrecognised value at startup.
 *
 * <p>The regression this pins is a real one: {@code compose.yaml} shipped
 * {@code APP_EVENTS_PUBLISHER=noop}, which matches neither publisher's
 * {@code @ConditionalOnProperty}. The container failed with an unsatisfied
 * dependency on {@code EventPublisher} — a message that names an interface
 * nobody configured and not the property that was wrong.
 */
class EventPropertiesTest {

    /**
     * {@code bindOrCreate}, not {@code bind}, because that is what Spring Boot
     * does for an {@code @ConfigurationProperties} bean: the record is
     * constructed even when nothing under the prefix is set, which is the only
     * way {@code @DefaultValue} is ever exercised.
     */
    private static EventProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("app.events", Bindable.of(EventProperties.class));
    }

    @Test
    @DisplayName("an unrecognised mode fails at binding, naming the property and the legal values")
    void rejectsUnknownMode() {
        assertThatThrownBy(() -> bind(Map.of("app.events.publisher", "noop")))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.events.publisher")
                .hasMessageContaining("[log, sqs]")
                .hasMessageContaining("noop");
    }

    @Test
    @DisplayName("both real modes bind")
    void acceptsBothModes() {
        assertThat(bind(Map.of("app.events.publisher", "log")).publisher()).isEqualTo("log");
        assertThat(bind(Map.of("app.events.publisher", "sqs")).publisher()).isEqualTo("sqs");
    }

    /**
     * The default has to match {@code matchIfMissing = true} on
     * {@code LoggingEventPublisher}. If it did not, this class would reject a
     * configuration that starts perfectly well today.
     */
    @Test
    @DisplayName("an absent property defaults to log, which is the publisher that matchIfMissing selects")
    void defaultsToLog() {
        assertThat(bind(Map.of("app.other", "x")).publisher()).isEqualTo("log");
    }
}
