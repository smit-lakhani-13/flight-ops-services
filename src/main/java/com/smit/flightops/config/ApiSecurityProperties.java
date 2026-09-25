package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.regex.Pattern;

/**
 * Encoded passwords for the two HTTP Basic identities, bound from {@code app.security.*}.
 * Each value carries a {@code DelegatingPasswordEncoder} prefix such as {@code {bcrypt}},
 * which names the algorithm. A deployment passes a {@code {bcrypt}} hash. The check does
 * not refuse {@code {noop}}, which marks a value that is not hashed and is what the
 * default profile ships. Binding leaves an unset {@code API_PASSWORD} as the literal
 * {@code ${API_PASSWORD}}, and this check is what stops that pod starting. It throws
 * from the constructor rather than using Bean Validation, whose startup report prints
 * the rejected value.
 *
 * @param apiPassword encoded password for the {@code api} user, used for {@code /api/v1/**}
 * @param opsPassword encoded password for the {@code ops} user, used for the actuator
 * @see "SECURITY.md, section Secrets"
 */
@ConfigurationProperties(prefix = "app.security")
public record ApiSecurityProperties(String apiPassword, String opsPassword) {

    /**
     * An algorithm id in braces at index 0, then at least one character. The id allows
     * any character except braces and whitespace, because the encoder's ids include
     * {@code pbkdf2@SpringSecurity_v5_8} and {@code SHA-256}.
     */
    private static final Pattern ENCODED = Pattern.compile("^\\{[^{}\\s]+\\}.+");

    public ApiSecurityProperties {
        requireEncoded("app.security.api-password", "API_PASSWORD", apiPassword);
        requireEncoded("app.security.ops-password", "OPS_PASSWORD", opsPassword);
    }

    /** Names the property and never the value, which may be a plaintext password. */
    private static void requireEncoded(String property, String variable, String value) {
        if (value != null && ENCODED.matcher(value).matches()) {
            return;
        }
        String detail = value == null || value.startsWith("${")
                ? variable + " is not set"
                : "the value is not shown";
        throw new IllegalArgumentException(property + " (" + variable + ") must be an encoded "
                + "password with a {id} algorithm prefix, for example {bcrypt}$2a$10$...; " + detail);
    }
}
