package com.smit.flightops;

import com.smit.flightops.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorisation matrix, run through the real filter chain with real credentials.
 * The {@code @WebMvcTest} slices run without filters and {@code ErrorContractTest} runs
 * as a pre-authenticated principal, so this is the only class that tests the whole
 * matrix. {@code OpenApiTest} checks that the document is public, that anonymous calls get
 * 401 and that the api user can read flights, so without this class a loosened scope, a
 * lost HEAD rule or a lost role rule would ship with every other test green.
 * {@code BearerTokenChallengeTest} covers the JWT-enabled context.
 *
 * <p>Its own H2 database, because it books a seat and other contexts count rows.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:securityrulestest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class SecurityRulesTest {

    @Autowired private MockMvc mockMvc;

    /** Matches the {noop} defaults in application.yml's default profile. */
    private static final String API_USER = "api";
    private static final String API_PASSWORD = "dev-secret";
    private static final String OPS_USER = "ops";
    private static final String OPS_PASSWORD = "dev-ops";

    // ---------------------------------------------------------------------
    // Probes. A failure here is a CrashLoopBackOff, not a 401.
    // ---------------------------------------------------------------------

    /**
     * The kubelet sends no credentials, so all three paths must answer anonymously. The
     * sub-paths are asserted separately: if EndpointRequest stopped covering them,
     * liveness would fall to denyAll and every pod would restart in a loop.
     */
    @Test
    @DisplayName("health, liveness and readiness are reachable with no credentials at all")
    void probesArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    /** Public does not mean detailed: a list of which dependency is down is reconnaissance. */
    @Test
    @DisplayName("an anonymous health check sees the status and nothing else")
    void anonymousHealthHidesComponents() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    /**
     * Pins {@code management.endpoint.health.roles: OPS}. Without it, when-authorized shows
     * the components, including the disk path, to any valid credential.
     */
    @Test
    @DisplayName("the api credential sees the health status but not the components")
    void apiHealthHidesComponents() throws Exception {
        mockMvc.perform(get("/actuator/health").with(httpBasic(API_USER, API_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    @DisplayName("an authorised operator sees the indicator breakdown the probe does not")
    void opsHealthShowsComponents() throws Exception {
        mockMvc.perform(get("/actuator/health").with(httpBasic(OPS_USER, OPS_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"));
    }

    // ---------------------------------------------------------------------
    // 401 vs 403, the distinction the error contract rests on.
    // ---------------------------------------------------------------------

    /**
     * The status, the challenge that tells a client how to authenticate, and the body,
     * which would be empty with Spring Security's defaults.
     */
    @Test
    @DisplayName("no credentials -> 401 with a challenge header and the application's own error body")
    void anonymousIsChallenged() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Basic realm=\"flight-ops-service\""))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    /** No username oracle: a real account with a bad password and a missing account look the same. */
    @Test
    @DisplayName("a wrong password is 401, and the message does not say whether the user exists")
    void wrongPasswordIsRejectedWithoutConfirmingTheUsername() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic(API_USER, "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"));

        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic("no-such-user", "whatever")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"));
    }

    /**
     * ops authenticates and is still refused, because it holds no read scope. This is the
     * assertion that fails if someone relaxes hasAuthority() to authenticated().
     */
    @Test
    @DisplayName("the ops credential authenticates and is still forbidden from the business API")
    void opsCredentialsCannotReadTheBusinessApi() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic(OPS_USER, OPS_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("the api credential authenticates and is still forbidden from the metrics endpoint")
    void apiCredentialsCannotReadMetrics() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(httpBasic(API_USER, API_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("metrics and prometheus need ROLE_OPS, and the ops user has it")
    void opsCanScrape() throws Exception {
        mockMvc.perform(get("/actuator/metrics").with(httpBasic(OPS_USER, OPS_PASSWORD)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus").with(httpBasic(OPS_USER, OPS_PASSWORD)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("metrics is not public")
    void metricsIsNotPublic() throws Exception {
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------------
    // Scopes. Read and write are separate.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the api credential can read a flight")
    void apiCredentialCanRead() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic(API_USER, API_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("UA123"));
    }

    /**
     * POST, PATCH and DELETE are separate rules in the DSL, so each is asserted: loosening
     * any one of them to permitAll, authenticated or the read scope fails here.
     */
    @Test
    @DisplayName("read scope alone cannot POST a booking, PATCH a flight's status or DELETE a flight")
    @WithMockUser(authorities = SecurityConfig.SCOPE_READ)
    void readScopeCannotWrite() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Smit Lakhani","seats":1,"idempotencyKey":"sec-denied-1"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(patch("/api/v1/flights/UA123/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"BOARDING\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/flights/UA789"))
                .andExpect(status().isForbidden());
    }

    /** Also proves CSRF is off: a state-changing POST with no CSRF token would otherwise be a 403. */
    @Test
    @DisplayName("write scope can POST a booking, with no CSRF token anywhere")
    void writeScopeCanBookWithoutACsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .with(httpBasic(API_USER, API_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Smit Lakhani","seats":1,"idempotencyKey":"sec-allowed-1"}
                                """))
                .andExpect(status().isCreated());
    }

    /**
     * jwt() injects an already-validated token, so this proves only the join: a token's
     * scopes map to the authority strings the rules are written against. Real decoding is
     * in {@code BearerTokenChallengeTest}.
     */
    @Test
    @DisplayName("a bearer token carrying the same scopes is authorised identically")
    void jwtScopesMapOntoTheSameRules() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123")
                        .with(jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.SCOPE_READ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics")
                        .with(jwt().authorities(new SimpleGrantedAuthority(SecurityConfig.SCOPE_READ))))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // Defaults.
    // ---------------------------------------------------------------------

    /**
     * denyAll() in action. An unmapped path answers 401, not 404, so an anonymous caller
     * cannot map the service by watching which URLs 404.
     */
    @Test
    @DisplayName("an unmapped path is denied rather than answered, and does not reveal that it is unmapped")
    void unmappedPathsAreDeniedByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/internal/whatever"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/some/other/thing"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * RequestIdFilter runs ahead of the security chain, so the 401 and 403 responses, the
     * ones a caller most often rings up about, carry an id too.
     */
    @Test
    @DisplayName("an unauthenticated 401 still carries X-Request-Id, and echoes the caller's")
    void everyResponseCarriesARequestId() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Request-Id"));

        mockMvc.perform(get("/api/v1/flights/UA123").header("X-Request-Id", "support-ticket-4471"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Request-Id", "support-ticket-4471"));

        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic(OPS_USER, OPS_PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(header().exists("X-Request-Id"));
    }

    /** STATELESS, shown by its consequence: no Set-Cookie, so no JSESSIONID for CSRF to protect. */
    @Test
    @DisplayName("no session is ever created — nothing sets a cookie")
    void authenticationCreatesNoSession() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic(API_USER, API_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    /**
     * Spring MVC serves HEAD for every GET mapping, and a GET rule does not cover it. The
     * ops case checks that HEAD is not simply permitted: it still needs the read scope.
     */
    @Test
    @DisplayName("HEAD follows the same rule as GET, in both directions")
    void headIsTreatedAsARead() throws Exception {
        mockMvc.perform(head("/api/v1/flights/UA123").with(httpBasic(API_USER, API_PASSWORD)))
                .andExpect(status().isOk());

        mockMvc.perform(head("/api/v1/flights/UA123").with(httpBasic(OPS_USER, OPS_PASSWORD)))
                .andExpect(status().isForbidden());

        mockMvc.perform(head("/api/v1/flights/UA123"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * PUT has no handler and must stay denied, which guards against relaxing anyRequest()
     * to authenticated(). The WARN must blame the rule set, not a credential that works,
     * and carry the caller's request id once, next to the service name.
     */
    @Test
    @DisplayName("a verb with no handler is still denied, and the WARN points at the rules, not the credential")
    void unhandledVerbsStayDenied(CapturedOutput output) throws Exception {
        mockMvc.perform(put("/api/v1/flights/UA123")
                        .with(httpBasic(API_USER, API_PASSWORD))
                        .header("X-Request-Id", "put-denied-1"))
                .andExpect(status().isForbidden());

        assertThat(output.getOut().lines().filter(line -> line.contains("Denied PUT /api/v1/flights/UA123")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .contains("no rule grants this method and path to its authorities")
                        .contains("[flight-ops-service,")
                        .contains(",put-denied-1] ")
                        .doesNotContain("[flight-ops-service] "));
    }
}
