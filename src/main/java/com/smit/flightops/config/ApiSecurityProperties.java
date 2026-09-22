package com.smit.flightops.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Credentials for the two HTTP Basic identities, bound from {@code
 * app.security.*}. Same reasoning as {@link AwsProperties}: bound once at
 * startup, immutable afterwards.
 *
 * <p><b>Both values are encoded passwords, not plaintext, and that is the whole
 * point of this class.</b> They are read by a {@code DelegatingPasswordEncoder},
 * so each one carries its own algorithm prefix — {@code {bcrypt}$2a$10$...} in
 * a real deployment, {@code {noop}dev-secret} on a developer's laptop. Three
 * things follow from that, and all three are the reason it is done this way:
 *
 * <ul>
 *   <li>A deployment never has to hand the service a plaintext password. What
 *       goes into the environment variable is a hash, so a leaked ConfigMap, a
 *       leaked environment dump or a shoulder-glance at {@code kubectl
 *       describe} does not hand anyone a working credential.</li>
 *   <li>The algorithm travels with the value, so rotating from bcrypt to
 *       argon2 is a change to the stored string and not a code change. Both
 *       encodings verify at the same time during a migration, which is what
 *       makes the rotation possible without a flag day.</li>
 *   <li>{@code {noop}} makes the dev credential unmistakable. A reviewer
 *       reading the YAML does not have to work out whether {@code dev-secret}
 *       is a real password that leaked into the repository: the prefix says
 *       out loud that it is not hashed and therefore not a secret.</li>
 * </ul>
 *
 * <p>The {@code prod} profile deliberately supplies no default for either
 * value, so a deployment that forgets them fails at startup with the property
 * name in the message rather than booting with a password somebody committed
 * in 2026.
 *
 * <h2>Why the constraints below are load-bearing, and not decoration</h2>
 * Leaving the property undefaulted is <b>not</b> enough on its own, and
 * believing it was is the most dangerous thing this class used to get wrong.
 * {@code @ConfigurationProperties} binding resolves placeholders with
 * {@code ignoreUnresolvablePlaceholders} set to true — unlike {@code @Value},
 * which fails. So with {@code API_PASSWORD} unset, this record binds happily to
 * the <i>literal seven-character string</i> {@code ${API_PASSWORD}}, the
 * context starts, both probes pass, and Kubernetes marks the pod Ready.
 *
 * <p>The failure then arrives one request later and in the worst possible
 * shape. {@code DelegatingPasswordEncoder} looks for the {@code {id}} prefix at
 * index 0; in {@code ${API_PASSWORD}} the brace is at index 1, so it throws
 * {@code IllegalArgumentException} rather than returning false. That is not an
 * {@code AuthenticationException}, so nothing in the filter chain catches it,
 * and {@code GlobalExceptionHandler} never sees it either — the throw happens
 * in a servlet filter, before {@code DispatcherServlet}. Every authenticated
 * request returns a bodyless 500, forever, on a pod that reports itself
 * healthy. A rollout would succeed and one hundred per cent of traffic fail.
 *
 * <p>{@code @NotBlank} alone does not close it, because the literal
 * {@code ${API_PASSWORD}} is not blank. The {@code @Pattern} is the half that
 * does the work: it requires the {@code {id}} prefix the encoder needs, which
 * an unresolved placeholder cannot have. The binding then fails at startup
 * naming {@code app.security.api-password}, which is what the paragraph above
 * always claimed and, until this annotation existed, was not true.
 *
 * @param apiPassword encoded password for the {@code api} user, the identity a
 *                    calling service uses for {@code /api/v1/**}
 * @param opsPassword encoded password for the {@code ops} user, the identity a
 *                    human or a scraper uses for the non-probe actuator
 *                    endpoints
 */
@Validated
@ConfigurationProperties(prefix = "app.security")
public record ApiSecurityProperties(

        @NotBlank
        @Pattern(regexp = ENCODED, message = MESSAGE)
        String apiPassword,

        @NotBlank
        @Pattern(regexp = ENCODED, message = MESSAGE)
        String opsPassword) {

    /**
     * A {@code DelegatingPasswordEncoder} value: an algorithm id in braces at
     * position zero, then at least one character of encoded password. Anchored
     * at the start on purpose — that is precisely where an unresolved
     * {@code ${...}} placeholder differs, and catching it here is the whole
     * point of the constraint.
     */
    static final String ENCODED = "^\\{[a-zA-Z0-9]+\\}.+";

    static final String MESSAGE =
            "must be an encoded password carrying a {id} algorithm prefix, for example "
            + "{bcrypt}$2a$10$... or {noop}dev-secret. A value like ${API_PASSWORD} means "
            + "the environment variable was never set.";
}
