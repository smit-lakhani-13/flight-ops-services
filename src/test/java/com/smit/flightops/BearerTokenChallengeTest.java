package com.smit.flightops;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The JWT resource server switched on, which {@code SecurityRulesTest} cannot show because
 * its context has no {@link JwtDecoder}. The decoder here trusts a key pair generated for
 * the run, so a token passes real validation only if this class signed it with the private
 * half; anything else gets the 401 challenge. Its own H2 database keeps its schema out of
 * the other contexts' tables.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:bearertokenchallengetest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(BearerTokenChallengeTest.GeneratedKeyDecoder.class)
class BearerTokenChallengeTest {

    /** The decoder trusts the public half; {@link #signedToken} signs with the private half. */
    private static final KeyPair KEYS = generateKeyPair();

    @TestConfiguration
    static class GeneratedKeyDecoder {
        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) KEYS.getPublic()).build();
        }
    }

    @Autowired private MockMvc mockMvc;

    /**
     * RFC 6750: an OAuth client reads {@code error="invalid_token"} to decide to refresh.
     * A Basic challenge here would tell it to send a password instead. The body is
     * asserted too, because it proves the resource server uses the JSON entry point.
     */
    @Test
    @DisplayName("a rejected bearer token gets a Bearer invalid_token challenge and the JSON body")
    void aRejectedBearerTokenGetsABearerChallenge() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"flight-ops-service\", error=\"invalid_token\""))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /** Both mechanisms are live in this mode, so a failed password must still say Basic. */
    @Test
    @DisplayName("a rejected password in the same context still gets the Basic challenge")
    void aRejectedPasswordStillGetsTheBasicChallenge() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic("api", "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"flight-ops-service\""))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    /**
     * A token that passes real decoding, so its {@code scope} claim reaches the rules
     * through the resource server's own converter, not a test post-processor. The GET is
     * the assertion that fails if that converter stops granting {@code SCOPE_} authorities.
     * This decoder checks the signature and the lifetime but no issuer or audience, so the
     * token carries neither; the decoder Boot builds from issuer-uri and audiences checks both.
     */
    @Test
    @DisplayName("a signed token with the read scope can read a flight, but not book or read metrics")
    void aSignedTokensScopeMapsOntoTheRules() throws Exception {
        String bearer = "Bearer " + signedToken("flights:read");

        mockMvc.perform(get("/api/v1/flights/UA123").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("UA123"));

        mockMvc.perform(post("/api/v1/bookings")
                        .header(HttpHeaders.AUTHORIZATION, bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Jane Doe","seats":1,\
                                "idempotencyKey":"jwt-denied-1"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"flight-ops-service\", error=\"insufficient_scope\", scope=\"flights:write\""))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /**
     * RFC 6750's {@code insufficient_scope} tells a client to get a token with more
     * scope. No scope lifts {@code denyAll()} or the ops role, so a 403 from either
     * carries no challenge, which keeps a client from asking for a token that cannot help.
     */
    @Test
    @DisplayName("a 403 that no scope can lift carries no insufficient_scope challenge")
    void aRefusalNoScopeLiftsHasNoChallenge() throws Exception {
        String bearer = "Bearer " + signedToken("flights:read flights:write");

        mockMvc.perform(put("/api/v1/flights/UA123").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/actuator/metrics").header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));
    }

    /**
     * Both mechanisms are live in this mode, and {@code insufficient_scope} would tell the
     * caller to fetch a token with more scope. One that signed in by password has no token
     * to swap, so its 403 carries no challenge even from a rule that asks for a scope: ops
     * holds no {@code flights:read}.
     */
    @Test
    @DisplayName("a 403 to a caller signed in by password carries no challenge, even from a scope rule")
    void aPasswordCallersRefusalHasNoChallenge() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123")
                        .with(httpBasic(SecurityRulesTest.opsUser(), SecurityRulesTest.opsPassword())))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /**
     * Spring Security's resource server serves RFC 9728 metadata here to any caller,
     * ahead of the rules, so this path answers 200 where {@code denyAll()} would answer
     * 401. Its default claims that tokens are bound to a client certificate; nothing here
     * checks one, so {@code SecurityConfig} turns the claim off.
     */
    @Test
    @DisplayName("the protected-resource metadata is public and does not claim certificate-bound tokens")
    void theProtectedResourceMetadataIsPublic() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource").value("http://localhost"))
                .andExpect(jsonPath("$.bearer_methods_supported[0]").value("header"))
                .andExpect(jsonPath("$.tls_client_certificate_bound_access_tokens").value(false));
    }

    /** RS256, the decoder's default algorithm, with a short expiry because the lifetime is checked. */
    private static String signedToken(String scope) throws JOSEException {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject("test-client")
                .claim("scope", scope)
                .expirationTime(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
                .build();
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        token.sign(new RSASSASigner(KEYS.getPrivate()));
        return token.serialize();
    }

    private static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
