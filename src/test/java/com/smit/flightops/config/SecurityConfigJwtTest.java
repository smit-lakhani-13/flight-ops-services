package com.smit.flightops.config;

import com.smit.flightops.security.ErrorResponseWriter;
import com.smit.flightops.security.JsonAccessDeniedHandler;
import com.smit.flightops.security.JsonAuthenticationEntryPoint;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The startup check on the properties Boot builds a {@link JwtDecoder} from. Boot's own
 * resource server auto-configuration runs, so each case gets the decoder, or none, that
 * the same properties would give a pod. Boot fetches a {@code jwk-set-uri} or
 * {@code issuer-uri} on the first token, not at startup, so the {@code .invalid} hosts
 * are never contacted. {@code BearerTokenChallengeTest} covers the other side: a decoder
 * bean defined in code, with none of these properties, still starts.
 */
class SecurityConfigJwtTest {

    private static final String JWT = "spring.security.oauth2.resourceserver.jwt";
    private static final String JWK_SET_URI = JWT + ".jwk-set-uri=https://idp.example.invalid/keys";
    private static final String ISSUER_URI = JWT + ".issuer-uri=https://idp.example.invalid/";

    @TempDir
    Path keys;

    @EnableConfigurationProperties(ApiSecurityProperties.class)
    static class Properties {
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(OAuth2ResourceServerAutoConfiguration.class))
            .withUserConfiguration(SecurityConfig.class, Properties.class, ErrorResponseWriter.class,
                    JsonAuthenticationEntryPoint.class, JsonAccessDeniedHandler.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues("app.security.api-password={noop}dev-secret",
                    "app.security.ops-password={noop}dev-ops");

    /** Signature and lifetime only: any tenant sharing that key set could call the API. */
    @Test
    @DisplayName("a jwk-set-uri without audiences stops startup and names the audiences property")
    void aJwkSetUriWithoutAudiencesStopsStartup() {
        runner.withPropertyValues(JWK_SET_URI)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageStartingWith(JWT + ".audiences must be set");
                });
    }

    /** The issuer signs for every client in its tenant; only the audience names this API. */
    @Test
    @DisplayName("an issuer-uri without audiences stops startup and names the audiences property")
    void anIssuerUriWithoutAudiencesStopsStartup() {
        runner.withPropertyValues(ISSUER_URI)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageStartingWith(JWT + ".audiences must be set");
                });
    }

    /** Boot adds no iss validator to a jwk-set-uri decoder unless issuer-uri is set too. */
    @Test
    @DisplayName("a jwk-set-uri with audiences but no issuer-uri stops startup and names issuer-uri")
    void aJwkSetUriWithoutAnIssuerStopsStartup() {
        runner.withPropertyValues(JWK_SET_URI, JWT + ".audiences[0]=flight-ops-service")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageStartingWith(JWT + ".issuer-uri must be set with " + JWT + ".jwk-set-uri");
                });
    }

    /** The comma-separated form is how an environment variable carries a list. */
    @Test
    @DisplayName("a jwk-set-uri with an issuer and comma-separated audiences starts with bearer tokens on")
    void aJwkSetUriWithAnIssuerAndAudiencesStarts() {
        runner.withPropertyValues(JWK_SET_URI, ISSUER_URI, JWT + ".audiences=flight-ops-service,flight-ops-admin")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class);
                    assertThat(filters(context)).anyMatch(BearerTokenAuthenticationFilter.class::isInstance);
                });
    }

    /**
     * A key the operator pinned is not a shared key set, so the issuer is not forced here.
     * Adding issuer-uri would also switch Boot to its discovery decoder.
     */
    @Test
    @DisplayName("a public-key-location with audiences starts without an issuer-uri")
    void aPublicKeyWithAudiencesStartsWithoutAnIssuer() throws Exception {
        runner.withPropertyValues(JWT + ".public-key-location=" + writePublicKey().toUri(),
                        JWT + ".audiences=flight-ops-service")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JwtDecoder.class);
                    assertThat(filters(context)).anyMatch(BearerTokenAuthenticationFilter.class::isInstance);
                });
    }

    /** The shipped configuration: no decoder, Basic alone, and the check never runs. */
    @Test
    @DisplayName("with no decoder property the service starts with HTTP Basic only")
    void noDecoderPropertyMeansBasicOnly() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(JwtDecoder.class);
            assertThat(filters(context))
                    .anyMatch(BasicAuthenticationFilter.class::isInstance)
                    .noneMatch(BearerTokenAuthenticationFilter.class::isInstance);
        });
    }

    private static List<Filter> filters(AssertableWebApplicationContext context) {
        return context.getBean(SecurityFilterChain.class).getFilters();
    }

    /** An RSA public key in the PEM form Boot reads from {@code public-key-location}. */
    private Path writePublicKey() throws NoSuchAlgorithmException, IOException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(generator.generateKeyPair().getPublic().getEncoded());
        return Files.writeString(keys.resolve("jwt.pub"),
                "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n");
    }
}
