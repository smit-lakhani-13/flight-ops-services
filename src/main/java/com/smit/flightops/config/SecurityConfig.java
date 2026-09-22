package com.smit.flightops.config;

import com.smit.flightops.security.JsonAccessDeniedHandler;
import com.smit.flightops.security.JsonAuthenticationEntryPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Who may call what.
 *
 * <p>The service is a machine-to-machine API: no browser, no login form, no
 * session, no cookie. Every design choice below follows from that one fact,
 * and the ones that look like they are missing something are the ones worth
 * reading the reasoning for.
 *
 * <h2>Two mechanisms, one authorisation model</h2>
 * A caller proves who it is in one of two ways, and the service then makes the
 * same decision either way:
 * <ul>
 *   <li><b>HTTP Basic</b> against the two identities in
 *       {@link ApiSecurityProperties}. This is what runs on a laptop, in the
 *       tests and behind a gateway that has already done the real
 *       authentication.</li>
 *   <li><b>A JWT bearer token</b>, validated as an OAuth 2 resource server,
 *       when {@code spring.security.oauth2.resourceserver.jwt.issuer-uri} is
 *       configured. This is what a real deployment uses.</li>
 * </ul>
 * The join between them is the authority string. Spring Security's default
 * {@code JwtGrantedAuthoritiesConverter} turns a token's {@code scope} claim
 * into {@code SCOPE_}-prefixed authorities, so a token carrying {@code
 * "scope": "flights:read flights:write"} arrives as {@code SCOPE_flights:read}
 * and {@code SCOPE_flights:write}. The Basic {@code api} user is granted
 * those same two strings verbatim. The consequence is the point: there is
 * exactly one authorisation rule set, and it does not know or care which
 * filter authenticated the request. Two rule sets that have to be kept in
 * agreement is how a service ends up enforcing a scope on one path and not the
 * other.
 *
 * <h2>Why the resource server is conditional</h2>
 * {@code oauth2ResourceServer(jwt)} needs a {@link JwtDecoder}, and Boot only
 * creates one when an issuer or a JWKS URI is configured. Declaring it
 * unconditionally would mean the application refuses to start anywhere an
 * issuer is not reachable — a laptop, CI, a test slice — which is how a
 * security layer ends up switched off in the one environment where it is
 * inconvenient. Asking an {@link ObjectProvider} instead means JWT support
 * appears exactly when there is something to validate against, and its absence
 * is logged rather than assumed.
 *
 * <h2>The rules, and what each one is defending</h2>
 * <ul>
 *   <li><b>Health is public.</b> A Kubernetes probe is an HTTP request from
 *       the kubelet with no credentials and no way to be given any. Requiring
 *       authentication on {@code /actuator/health/liveness} does not secure
 *       anything; it makes every probe fail, which makes Kubernetes restart
 *       every pod, forever. The endpoint leaks nothing anyway:
 *       {@code show-details: when-authorized} means an anonymous caller sees
 *       {@code {"status":"UP"}} and only an authenticated one sees which
 *       indicator is down.</li>
 *   <li><b>Every other actuator endpoint needs {@code ROLE_OPS}.</b>
 *       {@code /actuator/metrics} and {@code /actuator/prometheus} enumerate
 *       URI templates, response codes and latencies — a free map of the
 *       service's surface and its behaviour under load, which is
 *       reconnaissance, not telemetry, in the wrong hands.</li>
 *   <li><b>Reads need {@code flights:read}, writes need
 *       {@code flights:write}.</b> Split because they are different blast
 *       radii: a leaked read credential exposes data, a leaked write
 *       credential lets someone cancel other people's bookings. A single
 *       {@code flights} scope would make those the same incident.</li>
 *   <li><b>The OpenAPI document and the Swagger UI are public, and only for
 *       {@code GET}.</b> A description of an endpoint is not a credential for
 *       it: every operation the document lists still answers 401 without one,
 *       and the shapes it publishes are the same ones a 400 response already
 *       hands to an anonymous caller. What it buys is that a demo URL is
 *       explorable without a shared password, which is most of the reason to
 *       publish an API document at all. The permit is on {@code GET} and
 *       {@code HEAD} only, so a {@code POST} to a docs path falls to the
 *       {@code denyAll} below rather than reaching a handler that does not
 *       exist. {@code SWAGGER_UI_ENABLED=false} removes the UI for a
 *       deployment that disagrees; the document itself stays.</li>
 *   <li><b>Everything else is denied.</b> {@code anyRequest().denyAll()} means
 *       a controller added tomorrow is unreachable until someone writes a rule
 *       for it. The alternative, {@code authenticated()}, fails open: the new
 *       endpoint is live to any caller holding any credential, and nothing
 *       anywhere reports that a decision was skipped. Fail-closed is
 *       occasionally annoying and never a breach.</li>
 * </ul>
 *
 * <h2>The things deliberately switched off</h2>
 * <ul>
 *   <li><b>CSRF.</b> A CSRF attack works by making a browser replay a
 *       credential it is holding on the victim's behalf — a cookie. This API
 *       has no cookies and no session, so the browser has nothing to replay,
 *       and an {@code Authorization} header is never attached automatically by
 *       a cross-site request. Leaving CSRF on would reject every {@code POST}
 *       from every non-browser client with a 403 nobody could explain. The
 *       precondition is {@code STATELESS} below; disabling CSRF while
 *       returning a session cookie would be the actual vulnerability.</li>
 *   <li><b>Sessions.</b> {@code STATELESS} means no {@code HttpSession} is
 *       ever created and the {@code SecurityContext} is rebuilt per request.
 *       Two replicas behind a Service have no shared session store, so a
 *       session created on pod A is simply absent on pod B; stateless is not
 *       an optimisation here, it is the only thing that is correct. It also
 *       removes {@code JSESSIONID} from the response, which is what stops
 *       session fixation from being a question at all.</li>
 *   <li><b>Form login and HTTP 302s.</b> Never configured, so an
 *       unauthenticated request cannot be answered with a redirect to a login
 *       page that does not exist. {@link JsonAuthenticationEntryPoint}
 *       guarantees a 401 with a JSON body instead.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Written as constants because they appear in three places — the rules
     * below, the Basic user definitions, and the tests — and a typo in an
     * authority string is silent. {@code hasAuthority("SCOPE_flights:reed")}
     * compiles, passes review and denies every request.
     */
    public static final String SCOPE_READ = "SCOPE_flights:read";
    public static final String SCOPE_WRITE = "SCOPE_flights:write";
    private static final String ROLE_OPS = "OPS";

    private static final String API_PATHS = "/api/**";

    /**
     * The springdoc surface, spelled out rather than shortened to
     * {@code "/v3/**"}. A wildcard that wide is a standing invitation for the
     * next {@code /v3/something} to be public by accident, and this is the one
     * list in the file where a mistake is not caught by a test failing — it is
     * caught by somebody reading data they should not have.
     *
     * <p>{@code /v3/api-docs} and {@code /v3/api-docs/**} are separate entries
     * because the first is a literal path and Spring's pattern matcher does not
     * treat {@code /**} as covering zero segments in the way people assume.
     */
    private static final String[] DOC_PATHS = {
            "/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml",
            "/swagger-ui.html", "/swagger-ui/**"
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               JsonAuthenticationEntryPoint entryPoint,
                                               JsonAccessDeniedHandler accessDeniedHandler,
                                               ObjectProvider<JwtDecoder> jwtDecoder) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Matched by endpoint, not by literal path. EndpointRequest
                        // resolves management.endpoints.web.base-path at runtime, so
                        // moving the actuator to /internal does not silently leave
                        // this rule pointing at a path that no longer exists - which
                        // a hard-coded "/actuator/health/**" would, and it would fail
                        // open into the denyAll below rather than loudly.
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole(ROLE_OPS)
                        // Spring Boot's own error dispatch. Without this the error
                        // page is itself denied, and a 404 comes back as a 401 with
                        // the wrong body - a confusing failure to debug precisely
                        // because the symptom is in the error handler.
                        .requestMatchers("/error").permitAll()
                        // HEAD alongside GET, not folded into it. Spring MVC
                        // serves HEAD for every @GetMapping automatically, so
                        // HEAD /api/v1/flights/UA123 is a supported operation of
                        // this API - but HttpMethod.GET matches only the literal
                        // method, so without this line HEAD matched no rule and
                        // fell through to the denyAll below. The result was a 403
                        // for a caller holding flights:read on a resource it can
                        // GET, which is a wrong answer rather than a hole: uptime
                        // monitors, caches revalidating, and anything reading
                        // Content-Length before a GET all use HEAD, and each one
                        // also logged a WARN pointing ops at the credential
                        // instead of at the rule set.
                        // Before the /api/** rules, and it has to be: these
                        // paths are outside /api, so they would otherwise meet
                        // anyRequest().denyAll() and the UI would render an
                        // empty page with a 403 in the console.
                        .requestMatchers(HttpMethod.GET, DOC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, DOC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, API_PATHS).hasAuthority(SCOPE_READ)
                        .requestMatchers(HttpMethod.HEAD, API_PATHS).hasAuthority(SCOPE_READ)
                        .requestMatchers(HttpMethod.POST, API_PATHS).hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.PATCH, API_PATHS).hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.DELETE, API_PATHS).hasAuthority(SCOPE_WRITE)
                        .anyRequest().denyAll())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        // ifAvailable rather than getIfAvailable() != null: identical effect,
        // but it keeps the JWT wiring inside the branch that proved a decoder
        // exists, so there is no path on which a null decoder reaches the DSL.
        //
        // Customizer.withDefaults() takes the decoder as configured, which is
        // the point: whoever turns bearer tokens on must set BOTH
        // spring.security.oauth2.resourceserver.jwt.issuer-uri and .audiences.
        // issuer-uri alone checks the signature, the issuer and the lifetime,
        // and an issuer mints tokens for every client registered with it - so
        // a token minted for a different application in the same tenant is
        // correctly signed by the right issuer and would be accepted here.
        // The audience claim is the only field that names the intended API.
        // Boot's `audiences` property installs that validator inside the
        // decoder, which is why it is configuration rather than code: a
        // validator built here could be dropped in a refactor of this method
        // without any test noticing. application.yml and SECURITY.md carry the
        // same warning where an operator will actually read it.
        jwtDecoder.ifAvailable(decoder -> {
            try {
                // The two handlers are passed in again here, and leaving them
                // out was a real defect rather than tidiness. oauth2ResourceServer
                // does not read the entry point configured on exceptionHandling
                // below: OAuth2ResourceServerConfigurer installs its own
                // BearerTokenAuthenticationEntryPoint directly onto
                // BearerTokenAuthenticationFilter, and that filter catches the
                // AuthenticationException itself - so ExceptionTranslationFilter,
                // the only place the exceptionHandling entry point is consulted,
                // never runs for a bad token.
                //
                // The symptom was a split contract in the one deployment mode
                // that matters: a wrong Basic password returned
                // {"code":"UNAUTHENTICATED",...} and an expired bearer token
                // returned 401 with a zero-length body. A client parsing the
                // documented error shape on every non-2xx would throw a JSON
                // parse error and report "your service is broken" instead of
                // "refresh your token". Same for insufficient scope, via
                // BearerTokenAccessDeniedHandler.
                http.oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
                log.info("JWT resource server enabled — bearer tokens will be validated");
            } catch (Exception e) {
                // HttpSecurity.oauth2ResourceServer declares Exception, and a
                // Consumer cannot. Rethrowing unchecked is correct here: a
                // security layer that failed to configure must abort startup,
                // not log a warning and serve traffic with a rule missing.
                throw new IllegalStateException("Failed to configure the JWT resource server", e);
            }
        });
        if (jwtDecoder.getIfAvailable() == null) {
            log.info("No JwtDecoder configured — HTTP Basic only. "
                    + "Set spring.security.oauth2.resourceserver.jwt.issuer-uri "
                    + "and .audiences to accept bearer tokens.");
        }

        return http.build();
    }

    /**
     * {@code PasswordEncoderFactories.createDelegatingPasswordEncoder()} rather
     * than {@code new BCryptPasswordEncoder()}, and the difference matters the
     * day an algorithm has to change. A delegating encoder reads the {@code
     * {id}} prefix on each stored value and dispatches to the matching
     * algorithm, so bcrypt and argon2 hashes verify side by side and a rotation
     * is a data migration instead of a deployment where every credential stops
     * working at once.
     *
     * <p>It also refuses a value with no {@code {id}} prefix — but be precise
     * about <i>when</i>, because the obvious reading is wrong and this file
     * used to state it wrongly. The refusal happens on the first
     * authentication attempt, not at startup, and it arrives as an
     * {@code IllegalArgumentException} thrown inside a servlet filter: not an
     * {@code AuthenticationException}, so nothing in the chain catches it, and
     * not visible to {@code GlobalExceptionHandler}, which runs downstream of
     * {@code DispatcherServlet}. The caller gets a bodyless 500 on a pod that
     * reports itself healthy.
     *
     * <p>Making that a genuine startup failure is the job of the {@code @Pattern}
     * constraint on {@link ApiSecurityProperties}, which is where the reasoning
     * is written out in full. The encoder is the second line, not the first.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Two identities, in memory.
     *
     * <p>In memory is the honest choice for this service, not a shortcut. The
     * callers are other services and a metrics scraper, so there are two of
     * them and they change when the deployment changes, not when a user signs
     * up. A users table would add a schema, a migration and a database round
     * trip on the hot path of every single request, to store two rows that are
     * already in the configuration. When there is a real user population this
     * is the bean that changes, and nothing else in the file does — which is
     * the argument for authenticating through a {@code UserDetailsService} at
     * all rather than comparing strings in a filter.
     *
     * <p>The {@code api} user holds the same authority strings a JWT's scopes
     * map to, so the two mechanisms meet at the authorisation rules and nowhere
     * earlier.
     */
    @Bean
    UserDetailsService userDetailsService(ApiSecurityProperties properties) {
        return new InMemoryUserDetailsManager(
                User.withUsername("api")
                        .password(properties.apiPassword())
                        .authorities(SCOPE_READ, SCOPE_WRITE)
                        .build(),
                User.withUsername("ops")
                        .password(properties.opsPassword())
                        // .roles("OPS"), not .authorities("OPS"): roles() adds the
                        // ROLE_ prefix that hasRole("OPS") looks for. Mixing the two
                        // up is the single most common Spring Security bug, and it
                        // fails as a 403 on a correct password, which sends everyone
                        // looking at the credential instead of the authority.
                        .roles(ROLE_OPS)
                        .build());
    }
}
