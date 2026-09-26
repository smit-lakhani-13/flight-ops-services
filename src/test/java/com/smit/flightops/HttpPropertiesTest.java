package com.smit.flightops;

import com.smit.flightops.config.HttpProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The body limit's binding. A limit of zero would refuse every write while every read
 * and the health checks stayed green, so it stops startup instead.
 */
class HttpPropertiesTest {

    /** {@code bindOrCreate}, as Spring Boot does, so {@code @DefaultValue} runs with nothing set. */
    private static HttpProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("app.http", Bindable.of(HttpProperties.class));
    }

    @Test
    @DisplayName("an absent limit defaults to 16384 bytes, and a set one binds")
    void defaultsTo16KiB() {
        assertThat(bind(Map.of("app.other", "x")).maxBodyBytes()).isEqualTo(16384);
        assertThat(bind(Map.of("app.http.max-body-bytes", "65536")).maxBodyBytes()).isEqualTo(65536);
    }

    @Test
    @DisplayName("a limit of zero or less fails at binding, naming the property")
    void rejectsANonPositiveLimit() {
        for (String limit : new String[]{"0", "-1"}) {
            assertThatThrownBy(() -> bind(Map.of("app.http.max-body-bytes", limit)))
                    .as("limit %s", limit)
                    .rootCause()
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app.http.max-body-bytes");
        }
    }
}
