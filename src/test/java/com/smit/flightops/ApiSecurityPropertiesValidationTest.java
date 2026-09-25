package com.smit.flightops;

import com.smit.flightops.config.ApiSecurityProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {id}-prefix check on the two passwords, run through Boot's binder. Binding leaves an
 * unresolved placeholder as the literal {@code ${API_PASSWORD}}, so without this check an
 * unset variable in {@code prod} would start a pod that answers every authenticated
 * request with a 500. The check must also never repeat the value, which may be plaintext.
 */
class ApiSecurityPropertiesValidationTest {

    private static final String OPS_DEFAULT = "{noop}dev-ops";

    /** {@code bindOrCreate}, as Boot does for the bean: the record is built even with nothing set. */
    private static ApiSecurityProperties bind(String apiPassword, String opsPassword) {
        Map<String, String> properties = new HashMap<>();
        if (apiPassword != null) {
            properties.put("app.security.api-password", apiPassword);
        }
        if (opsPassword != null) {
            properties.put("app.security.ops-password", opsPassword);
        }
        return new Binder(new MapConfigurationPropertySource(properties))
                .bindOrCreate("app.security", Bindable.of(ApiSecurityProperties.class));
    }

    /** The case the check exists for: the message names the property and says the variable is unset. */
    @Test
    @DisplayName("an unresolved placeholder fails binding and names the unset variable")
    void anUnresolvedPlaceholderIsRejected() {
        assertThatThrownBy(() -> bind("${API_PASSWORD}", "${OPS_PASSWORD}"))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.api-password")
                .hasMessageContaining("API_PASSWORD is not set");
    }

    /** Each field is checked on its own, so a good api password cannot mask a bad ops one. */
    @Test
    @DisplayName("the ops password is checked on its own and named in the failure")
    void theOpsPasswordIsCheckedOnItsOwn() {
        assertThatThrownBy(() -> bind("{noop}dev-secret", "${OPS_PASSWORD}"))
                .rootCause()
                .hasMessageContaining("app.security.ops-password")
                .hasMessageContaining("OPS_PASSWORD is not set");
    }

    /**
     * Shapes the encoder would throw on per request, or, for {@code {noop}} with nothing
     * after it, a password no request can match. The message must not contain the
     * value: a plaintext password set without its prefix would otherwise reach the log.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "dev-secret",          // plaintext pasted into the environment
            "{}dev-secret",        // empty algorithm id
            "{noop}",              // prefix with no password after it
            " {noop}dev-secret",   // leading space, so the brace is not at index 0
            "bcrypt}$2a$10$abc"    // opening brace lost in a copy-paste
    })
    @DisplayName("a malformed value fails binding without the value appearing in the message")
    void malformedEncodedPasswordsAreRejected(String bad) {
        assertThatThrownBy(() -> bind(bad, OPS_DEFAULT))
                .rootCause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.security.api-password")
                .hasMessageContaining("the value is not shown")
                .hasMessageNotContaining(bad);
    }

    /** Includes an id with {@code @} and {@code _} in it, which the encoder registers. */
    @ParameterizedTest
    @ValueSource(strings = {
            "{noop}dev-secret",
            "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
            "{pbkdf2@SpringSecurity_v5_8}121d6e31e4311b8f86148a1f52ad230ba826a490b92480b5"
                    + "97f5143ed5b3065f16244b86fea0666610927ed85357315d"
    })
    @DisplayName("a value with a well-formed {id} prefix binds unchanged")
    void correctlyEncodedPasswordsPass(String good) {
        ApiSecurityProperties bound = bind(good, good);
        assertThat(bound.apiPassword()).isEqualTo(good);
        assertThat(bound.opsPassword()).isEqualTo(good);
    }

    /** With nothing under app.security, Boot still builds the record, from nulls. */
    @Test
    @DisplayName("an absent property fails binding too")
    void nullIsRejected() {
        assertThatThrownBy(() -> bind(null, null))
                .rootCause()
                .hasMessageContaining("app.security.api-password")
                .hasMessageContaining("API_PASSWORD is not set");
    }
}
