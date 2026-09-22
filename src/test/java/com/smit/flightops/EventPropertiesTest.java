package com.smit.flightops;

import com.smit.flightops.config.EventProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The event transport mode refusing an unrecognised value at startup.
 *
 * <p>A value such as {@code noop} matches neither publisher's
 * {@code @ConditionalOnProperty}. Without this check the application fails on an
 * unsatisfied dependency on {@code EventPublisher}, which does not name the property.
 */
class EventPropertiesTest {

    /**
     * {@code bindOrCreate}, as Spring Boot does for an {@code @ConfigurationProperties}
     * bean, so the record is built even with nothing set and {@code @DefaultValue} runs.
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

    /**
     * The whole application, with the outbox poller enabled as it is by default.
     * {@code OutboxPublisher} takes {@code EventProperties} before {@code EventPublisher},
     * so the named binding failure is what stops startup. The settings go in as
     * arguments because {@code application.yml} outranks builder defaults.
     */
    @Test
    @DisplayName("an unrecognised mode stops the application, and the failure names the property")
    void applicationStartupNamesTheProperty() {
        SpringApplicationBuilder app = new SpringApplicationBuilder(FlightOpsServiceApplication.class)
                .web(WebApplicationType.NONE);

        assertThatThrownBy(() -> {
            try (var context = app.run(
                    "--app.events.publisher=noop",
                    "--app.outbox.enabled=true",
                    "--spring.datasource.url=jdbc:h2:mem:eventmode;DB_CLOSE_DELAY=-1",
                    "--spring.main.banner-mode=off")) {
                assertThat(context.isActive()).isFalse();
            }
        })
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

    /** The default has to match {@code matchIfMissing = true} on {@code LoggingEventPublisher}. */
    @Test
    @DisplayName("an absent property defaults to log, which is the publisher that matchIfMissing selects")
    void defaultsToLog() {
        assertThat(bind(Map.of("app.other", "x")).publisher()).isEqualTo("log");
    }
}
