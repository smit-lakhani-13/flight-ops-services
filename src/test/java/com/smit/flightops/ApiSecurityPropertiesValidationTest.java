package com.smit.flightops;

import com.smit.flightops.config.ApiSecurityProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression test for the worst defect this repository has had.
 *
 * <p>The {@code prod} profile supplies no default for {@code API_PASSWORD}, and
 * for a long time that was believed to be enough — the comment in
 * {@code application.yml}, the Javadoc on {@link ApiSecurityProperties}, the
 * README and {@code SecurityConfig} all said a missing value would fail at
 * startup. None of them were true.
 *
 * <p>{@code @ConfigurationProperties} binding resolves placeholders with
 * {@code ignoreUnresolvablePlaceholders} set to true, unlike {@code @Value}. So
 * with the environment variable unset, the record bound to the literal string
 * {@code ${API_PASSWORD}}, the context started, both probes passed and
 * Kubernetes marked the pod Ready. The failure arrived one request later:
 * {@code DelegatingPasswordEncoder} looks for its {@code {id}} prefix at index
 * zero, found the brace at index one, and threw {@code
 * IllegalArgumentException} — not an {@code AuthenticationException}, so no
 * filter caught it, and thrown before {@code DispatcherServlet}, so
 * {@code GlobalExceptionHandler} never saw it either. Every authenticated
 * request returned a bodyless 500, forever, on a pod reporting itself healthy.
 *
 * <p>This test pins the constraint that closes it. Note which assertion is
 * load-bearing: {@code @NotBlank} passes for {@code ${API_PASSWORD}}, because a
 * literal placeholder is not blank. The {@code @Pattern} is the one that
 * matters, and the unresolved-placeholder case below is the reason it exists.
 */
class ApiSecurityPropertiesValidationTest {

    private static final ValidatorFactory FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = FACTORY.getValidator();

    private static int violations(String apiPassword, String opsPassword) {
        return VALIDATOR.validate(new ApiSecurityProperties(apiPassword, opsPassword)).size();
    }

    @Test
    @DisplayName("an unresolved placeholder is rejected — the case the whole constraint exists for")
    void anUnresolvedPlaceholderIsRejected() {
        assertThat(violations("${API_PASSWORD}", "${OPS_PASSWORD}"))
                .as("binding must fail rather than accept the literal placeholder")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("@NotBlank alone would not have caught it — the placeholder is not blank")
    void notBlankAloneWouldNotHaveCaughtIt() {
        // Stated as a test rather than a comment so that anyone tempted to
        // "simplify" the record down to @NotBlank sees why that is a revert.
        assertThat("${API_PASSWORD}".isBlank()).isFalse();
        assertThat(violations("${API_PASSWORD}", "{noop}dev-ops")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "dev-secret",          // plaintext pasted into the environment
            "{}dev-secret",        // empty algorithm id
            "{noop}",              // prefix with no password after it
            " {noop}dev-secret",   // leading space, so the brace is not at index 0
            "bcrypt}$2a$10$abc"    // opening brace lost in a copy-paste
    })
    @DisplayName("a value the password encoder would choke on is rejected at binding time")
    void malformedEncodedPasswordsAreRejected(String bad) {
        assertThat(violations(bad, "{noop}dev-ops"))
                .as("'%s' would reach DelegatingPasswordEncoder and throw per-request", bad)
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{noop}dev-secret",
            "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy",
            "{argon2}$argon2id$v=19$m=16384,t=2,p=1$abc$def"
    })
    @DisplayName("a properly encoded value passes, whichever algorithm it names")
    void correctlyEncodedPasswordsPass(String good) {
        assertThat(violations(good, good)).isZero();
    }

    @Test
    @DisplayName("null is rejected too — an absent property is not a valid credential")
    void nullIsRejected() {
        // Two violations per field: @NotBlank and @Pattern both fire on null?
        // No — @Pattern passes on null by specification, so this is exactly one
        // each, and that asymmetry is why both annotations are present.
        assertThat(violations(null, null)).isEqualTo(2);
    }
}
