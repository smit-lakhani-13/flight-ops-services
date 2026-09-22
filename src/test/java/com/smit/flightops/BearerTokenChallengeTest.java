package com.smit.flightops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 401 challenge with the JWT resource server switched on, which {@code SecurityRulesTest}
 * cannot show because its context has no {@link JwtDecoder}. The decoder here trusts a key
 * generated for the run, so any token a test sends is rejected by real validation. Its own
 * H2 database keeps its schema out of the other contexts' tables.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:bearertokenchallengetest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@Import(BearerTokenChallengeTest.GeneratedKeyDecoder.class)
class BearerTokenChallengeTest {

    @TestConfiguration
    static class GeneratedKeyDecoder {
        @Bean
        JwtDecoder jwtDecoder() throws NoSuchAlgorithmException {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return NimbusJwtDecoder.withPublicKey((RSAPublicKey) generator.generateKeyPair().getPublic()).build();
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
}
