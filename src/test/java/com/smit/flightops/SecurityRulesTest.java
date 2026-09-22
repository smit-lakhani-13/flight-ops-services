package com.smit.flightops;

import com.smit.flightops.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The authorisation matrix, run through the real filter chain with real
 * credentials.
 *
 * <p>This is the only place in the suite where {@code SecurityConfig} is
 * actually enforced. The {@code @WebMvcTest} slices run with
 * {@code addFilters = false} and {@code ErrorContractTest} runs as a
 * pre-authenticated principal, both for reasons written down where they
 * happen. The consequence is that if this class is deleted, nothing else
 * fails and the service ships unprotected — so it carries the cases that
 * matter most, not the ones that are easiest to write.
 *
 * <p>Two of them are the ones I would want to see in a review:
 * <ul>
 *   <li>{@link #opsCredentialsCannotReadTheBusinessApi()} — the {@code ops}
 *       user authenticates perfectly and is still refused. That is the
 *       difference between {@code authenticated()} and {@code hasAuthority()},
 *       and it is the assertion that fails if someone "simplifies" the rules.</li>
 *   <li>{@link #readScopeCannotWrite()} — a valid caller with the wrong half of
 *       the scope pair. Without it, granting one scope to everything would pass
 *       every other test in this file.</li>
 * </ul>
 *
 * <p>Its own H2 database: this shares the mock-servlet context key with the
 * other {@code @SpringBootTest} classes, and it books a seat. A private URL
 * keeps its writes off the tables those tests count rows in.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:securityrulestest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
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
     * The kubelet sends no credentials and cannot be given any, so all three
     * health paths must answer anonymously. The two sub-paths are asserted
     * separately on purpose: {@code EndpointRequest.to(HealthEndpoint.class)}
     * is documented to cover an endpoint's sub-paths, and "documented to" is
     * not the same as "does, on this version, with these group names". If it
     * ever stops covering them, liveness falls through to {@code denyAll} and
     * every pod restarts on a five-second loop.
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

    /**
     * Public does not mean detailed. {@code show-details: when-authorized}
     * is what keeps the database's reachability, the disk free space and the
     * names of the configured indicators out of an anonymous response — a
     * public endpoint that lists exactly which dependency is down is a
     * reconnaissance tool.
     */
    @Test
    @DisplayName("an anonymous health check sees the status and nothing else")
    void anonymousHealthHidesComponents() throws Exception {
        mockMvc.perform(get("/actuator/health"))
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
    // 401 vs 403 — the distinction the whole error contract rests on.
    // ---------------------------------------------------------------------

    /**
     * Three assertions, one per thing that would otherwise be assumed: the
     * status, the challenge header that tells a client how to authenticate,
     * and the body shape. The body matters because every other error this
     * service returns is {@code {code, message, timestamp}}, and Spring
     * Security's default is an empty one.
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

    @Test
    @DisplayName("a wrong password is 401, and the message does not say whether the user exists")
    void wrongPasswordIsRejectedWithoutConfirmingTheUsername() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic(API_USER, "not-the-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                // No username oracle: a real account with a bad password and a
                // account that does not exist must be indistinguishable.
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"));

        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic("no-such-user", "whatever")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authentication is required to access this resource"));
    }

    /**
     * The case that separates authentication from authorisation. {@code ops}
     * proves who it is and is still refused, because who it is does not
     * include a read scope. Returning 401 here would be the common mistake and
     * would send a correctly-configured client into a credential refresh loop
     * that can never succeed.
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
    // Scopes. Read and write are separate on purpose.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the api credential can read a flight")
    void apiCredentialCanRead() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic(API_USER, API_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightNumber").value("UA123"));
    }

    /**
     * A caller holding only {@code flights:read} may not book, and may not
     * cancel. Both verbs are asserted because they are separate rules in the
     * DSL — {@code POST} and {@code DELETE} are listed individually, and
     * forgetting one is invisible until someone deletes something.
     */
    @Test
    @DisplayName("read scope alone cannot POST a booking or DELETE a flight")
    @WithMockUser(authorities = SecurityConfig.SCOPE_READ)
    void readScopeCannotWrite() throws Exception {
        mockMvc.perform(post("/api/v1/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"flightNumber":"UA123","passengerName":"Smit Lakhani","seats":1,"idempotencyKey":"sec-denied-1"}
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(delete("/api/v1/flights/UA789"))
                .andExpect(status().isForbidden());
    }

    /**
     * The positive half of the pair, and it doubles as the proof that CSRF is
     * genuinely off: this is a state-changing {@code POST} carrying no CSRF
     * token. With Spring Security's default configuration it would be a 403,
     * and every non-browser client would be broken.
     */
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
     * The same rules, reached through the other authentication mechanism.
     *
     * <p>Honest about what this proves and what it does not. {@code jwt()}
     * injects an already-validated {@code JwtAuthenticationToken}, so it says
     * nothing about signature verification, issuer checks or expiry — there is
     * no {@code JwtDecoder} in this context to exercise, because none is
     * configured without an issuer URI. What it does prove is the half that is
     * this application's own code rather than the framework's: that the
     * authority strings a token's {@code scope} claim maps to are exactly the
     * strings the rules are written against. That is the join between the two
     * mechanisms, and it is the part that would silently drift.
     */
    @Test
    @DisplayName("a bearer token carrying the same scopes is authorised identically")
    void jwtScopesMapOntoTheSameRules() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123")
                        .with(jwt().authorities(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority(SecurityConfig.SCOPE_READ))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/metrics")
                        .with(jwt().authorities(new org.springframework.security.core.authority
                                .SimpleGrantedAuthority(SecurityConfig.SCOPE_READ))))
                .andExpect(status().isForbidden());
    }

    // ---------------------------------------------------------------------
    // Defaults.
    // ---------------------------------------------------------------------

    /**
     * {@code anyRequest().denyAll()} in action. An unmapped path answers 401
     * rather than 404, which is deliberate twice over: nothing outside the
     * declared rules is reachable, and an anonymous caller cannot map the
     * service by watching which URLs 404 and which 401.
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
     * STATELESS, proved by its observable consequence rather than by reading
     * the configuration back. No {@code Set-Cookie} means no {@code
     * JSESSIONID}, which means a second replica behind the Service can serve
     * the next request with no shared session store — and it is also what
     * makes disabling CSRF safe rather than reckless.
     */
    @Test
    @DisplayName("no session is ever created — nothing sets a cookie")
    void authenticationCreatesNoSession() throws Exception {
        mockMvc.perform(get("/api/v1/flights/UA123").with(httpBasic(API_USER, API_PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    /**
     * HEAD, because Spring MVC serves it for every {@code @GetMapping} without
     * being asked and {@code requestMatchers(HttpMethod.GET, ...)} does not
     * cover it.
     *
     * <p>Without an explicit rule, HEAD matched nothing and fell through to
     * {@code anyRequest().denyAll()}, so a caller holding {@code flights:read}
     * got a 403 on a resource it could GET — a wrong answer rather than a hole,
     * and one that lands on exactly the clients most likely to use HEAD: uptime
     * monitors, caches revalidating, anything reading {@code Content-Length}
     * before committing to a body. Each one also logged a WARN about a missing
     * authority, pointing whoever is on call at the credential instead of at
     * the rule set.
     *
     * <p>The ops case is asserted too, so the fix cannot be "let HEAD through"
     * — read still has to mean read.
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
     * The methods that genuinely have no handler must stay denied.
     *
     * <p>This is the guard on the fix above: the temptation when HEAD comes
     * back 403 is to relax {@code anyRequest()} to {@code authenticated()},
     * which would make every unhandled verb reachable by any credential. PUT
     * has no controller and must not become one rule change away from having
     * one.
     */
    @Test
    @DisplayName("a verb with no handler is still denied, even for a fully authorised caller")
    void unhandledVerbsStayDenied() throws Exception {
        mockMvc.perform(put("/api/v1/flights/UA123").with(httpBasic(API_USER, API_PASSWORD)))
                .andExpect(status().isForbidden());
    }
}
