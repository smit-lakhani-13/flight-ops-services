package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 * @param apiPassword encoded password for the {@code api} user, the identity a
 *                    calling service uses for {@code /api/v1/**}
 * @param opsPassword encoded password for the {@code ops} user, the identity a
 *                    human or a scraper uses for the non-probe actuator
 *                    endpoints
 */
@ConfigurationProperties(prefix = "app.security")
public record ApiSecurityProperties(String apiPassword, String opsPassword) {
}
