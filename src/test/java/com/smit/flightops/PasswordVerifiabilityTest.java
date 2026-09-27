package com.smit.flightops;

import com.smit.flightops.config.ApiSecurityProperties;
import com.smit.flightops.config.SecurityConfig;
import com.smit.flightops.security.ErrorResponseWriter;
import com.smit.flightops.security.JsonAccessDeniedHandler;
import com.smit.flightops.security.JsonAuthenticationEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup self-check in {@link SecurityConfig}: a password the delegating encoder
 * cannot verify at all must stop the context, naming the property, instead of starting a
 * pod that answers every authenticated request with a 500. Under {@code prod}, an id
 * that is not an adaptive hash, such as {@code {noop}} or {@code {ldap}}, must stop it
 * too. It runs {@code SecurityConfig} alone, with the beans its filter chain needs, so
 * each case is a fresh context.
 */
class PasswordVerifiabilityTest {

    /**
     * A pbkdf2 hash of "s3cret". Its id, {@code pbkdf2@SpringSecurity_v5_8}, is registered
     * and holds {@code @} and {@code _}, so the prefix check in {@code ApiSecurityProperties}
     * must accept more than letters and digits.
     */
    private static final String PBKDF2 = "{pbkdf2@SpringSecurity_v5_8}"
            + "121d6e31e4311b8f86148a1f52ad230ba826a490b92480b597f5143ed5b3065f16244b86fea0666610927ed85357315d";

    /** A bcrypt hash, the form the deployment's Secret holds. */
    private static final String BCRYPT = "{bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @EnableConfigurationProperties(ApiSecurityProperties.class)
    static class Properties {
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(SecurityConfig.class, Properties.class, ErrorResponseWriter.class,
                    JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(Clock.class, Clock::systemUTC);

    /** A typo in the id passes the prefix check, so only the self-check can catch it. */
    @Test
    @DisplayName("an unknown algorithm id stops startup and names the property")
    void anUnknownAlgorithmIdStopsStartup() {
        runner.withPropertyValues("app.security.api-password={foo}bar",
                        "app.security.ops-password={noop}dev-ops")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("app.security.api-password cannot be verified")
                            .rootCause()
                            .hasMessageContaining("no password encoder mapped for the id 'foo'");
                });
    }

    /**
     * argon2 needs BouncyCastle, which this build leaves out, so the encoder throws
     * NoClassDefFoundError. The check catches LinkageError for this case.
     */
    @Test
    @DisplayName("an argon2 hash without BouncyCastle on the classpath stops startup")
    void argon2WithoutBouncyCastleStopsStartup() {
        runner.withPropertyValues("app.security.api-password={noop}dev-secret",
                        "app.security.ops-password={argon2}$argon2id$v=19$m=16384,t=2,p=1$abc$def")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("app.security.ops-password cannot be verified")
                            .hasStackTraceContaining("org/bouncycastle");
                });
    }

    /** The positive case: a registered id with {@code @} in it starts and verifies. */
    @Test
    @DisplayName("a pbkdf2 hash starts the context and verifies the password it encodes")
    void aPbkdf2HashStartsTheContext() {
        runner.withPropertyValues("app.security.api-password=" + PBKDF2,
                        "app.security.ops-password={noop}dev-ops")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    String stored = context.getBean(UserDetailsService.class).loadUserByUsername("api").getPassword();
                    assertThat(context.getBean(PasswordEncoder.class).matches("s3cret", stored)).isTrue();
                });
    }

    /**
     * A {@code {noop}} value is the password itself, so under {@code prod} a leaked Secret
     * or an environment dump would be a working login. The value must not reach the log.
     * Each account in turn, with the other one hashed.
     */
    @ParameterizedTest
    @CsvSource({"app.security.api-password, API_PASSWORD, app.security.ops-password",
            "app.security.ops-password, OPS_PASSWORD, app.security.api-password"})
    @DisplayName("under the prod profile a {noop} password stops startup, naming the property and not the value")
    void theProdProfileRefusesAnUnhashedPassword(String property, String variable, String other) {
        runner.withPropertyValues("spring.profiles.active=prod",
                        other + "=" + PBKDF2,
                        property + "={noop}leaked-value")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTrace(context.getStartupFailure()))
                            .contains(property + " (" + variable + ") must be a hashed password "
                                    + "under the prod profile")
                            .doesNotContain("leaked-value");
                });
    }

    /**
     * {@code {ldap}} compares a value with no {@code {SHA}} or {@code {SSHA}} prefix as plain
     * text, so this value passes the self-check and would log in with "plain-text".
     */
    @Test
    @DisplayName("under the prod profile an {ldap} value kept in plain text stops startup")
    void theProdProfileRefusesAnLdapValueInPlainText() {
        runner.withPropertyValues("spring.profiles.active=prod",
                        "app.security.api-password={ldap}plain-text",
                        "app.security.ops-password=" + PBKDF2)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(stackTrace(context.getStartupFailure()))
                            .contains("app.security.api-password (API_PASSWORD) must be a hashed password "
                                    + "under the prod profile")
                            .doesNotContain("plain-text");
                });
    }

    /** The default profile's {@code {noop}} values are the other tests' baseline; prod takes bcrypt and pbkdf2. */
    @Test
    @DisplayName("under the prod profile hashed passwords start the context")
    void theProdProfileAcceptsHashedPasswords() {
        runner.withPropertyValues("spring.profiles.active=prod",
                        "app.security.api-password=" + PBKDF2,
                        "app.security.ops-password=" + BCRYPT)
                .run(context -> assertThat(context).hasNotFailed());
    }

    private static String stackTrace(Throwable failure) {
        StringWriter out = new StringWriter();
        failure.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
