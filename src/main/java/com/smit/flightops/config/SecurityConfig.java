package com.smit.flightops.config;

import com.smit.flightops.security.JsonAccessDeniedHandler;
import com.smit.flightops.security.JsonAuthenticationEntryPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
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
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Who may call what. The callers are other services with no browser, session or cookie,
 * so sessions, CSRF and form login are off. HTTP Basic is always on, and a JWT resource
 * server joins it when Boot has a {@link JwtDecoder}; a token's scopes and the Basic
 * {@code api} user carry the same authority strings, so one rule set covers both. The
 * last rule is {@code denyAll()}, so a request that no rule above matches is denied,
 * such as a {@code PUT} under {@code /api/**}. A new controller there is covered by the
 * scope rules as soon as it ships, for GET, HEAD, POST, PATCH and DELETE.
 *
 * @see "SECURITY.md, and adr/0005 and adr/0006"
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    /**
     * Shared by the rules, the Basic users and the tests, because a typo in an authority
     * string compiles and then denies every request.
     */
    public static final String SCOPE_READ = "SCOPE_flights:read";
    public static final String SCOPE_WRITE = "SCOPE_flights:write";
    private static final String ROLE_OPS = "OPS";

    private static final String API_PATHS = "/api/**";

    /** Boot's prefix for the properties it builds a {@link JwtDecoder} from. */
    private static final String JWT = "spring.security.oauth2.resourceserver.jwt";

    /**
     * The springdoc paths, listed one by one so a future {@code /v3/something} is not
     * public by accident. A trailing {@code /**} also matches zero segments, so
     * {@code /v3/api-docs/**} covers {@code /v3/api-docs} itself.
     */
    private static final String[] DOC_PATHS = {
            "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/**"
    };

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               JsonAuthenticationEntryPoint entryPoint,
                                               JsonAccessDeniedHandler accessDeniedHandler,
                                               ObjectProvider<JwtDecoder> jwtDecoder,
                                               Environment environment) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // Public because the kubelet has no credentials; the health
                        // `roles` setting in application.yml keeps the components for ops.
                        // By endpoint, not by path: EndpointRequest follows a moved
                        // management base path. A hard-coded "/actuator/health/**"
                        // would go stale, and the probes would then fail closed on the
                        // ops rule below and restart the pods in a loop.
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole(ROLE_OPS)
                        // The container's error dispatch. Denied, it would turn the
                        // firewall's 400 for a refused URL into a 401 about the error page.
                        .requestMatchers("/error").permitAll()
                        // Outside /api, so without these the docs meet denyAll().
                        .requestMatchers(HttpMethod.GET, DOC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.HEAD, DOC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.GET, API_PATHS).hasAuthority(SCOPE_READ)
                        // Spring MVC serves HEAD for every @GetMapping, and
                        // HttpMethod.GET does not match HEAD, so it needs its own rule.
                        .requestMatchers(HttpMethod.HEAD, API_PATHS).hasAuthority(SCOPE_READ)
                        .requestMatchers(HttpMethod.POST, API_PATHS).hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.PATCH, API_PATHS).hasAuthority(SCOPE_WRITE)
                        .requestMatchers(HttpMethod.DELETE, API_PATHS).hasAuthority(SCOPE_WRITE)
                        .anyRequest().denyAll())
                // Safe only while nothing issues a session cookie, hence STATELESS.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        // The decoder is Boot's, as configured. An issuer signs tokens for every client
        // in its tenant, and only the audience names this API, so
        // requireIssuerAndAudience stops startup without jwt.audiences. Boot's
        // `audiences` property puts that validator inside the decoder, where a refactor
        // here cannot drop it.
        jwtDecoder.ifAvailable(decoder -> {
            String checks = requireIssuerAndAudience(environment);
            try {
                // The handlers are passed again because the resource server installs
                // its own on the bearer filter, which never consults exceptionHandling().
                // Without them a bad token gets a 401 with an empty body.
                http.oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
                log.info("JWT resource server enabled; bearer tokens will be validated ({})", checks);
            } catch (Exception e) {
                // A security layer that failed to configure must stop startup.
                throw new IllegalStateException("Failed to configure the JWT resource server", e);
            }
        });
        if (jwtDecoder.getIfAvailable() == null) {
            log.info("No JwtDecoder configured; HTTP Basic only. "
                    + "Set spring.security.oauth2.resourceserver.jwt.issuer-uri "
                    + "and .audiences to accept bearer tokens.");
        }

        return http.build();
    }

    /**
     * Stops startup when Boot built the decoder from properties that leave a claim
     * unchecked, and otherwise says what the decoder checks, for the startup log. Boot
     * builds one from {@code issuer-uri}, {@code jwk-set-uri} or
     * {@code public-key-location}, but adds the aud validator only for
     * {@code audiences} and the iss validator only for {@code issuer-uri}.
     * {@code audiences} is always required. {@code issuer-uri} is required beside
     * {@code jwk-set-uri}, whose key set may be shared by every tenant of a provider,
     * and not beside a {@code public-key-location} alone, a key the operator pinned.
     * A {@link JwtDecoder} bean defined in code, with none of the properties set,
     * brings its own validators and passes. The Binder, not {@code getProperty}, so
     * that {@code audiences[0]} and a comma-separated environment variable both bind.
     */
    private static String requireIssuerAndAudience(Environment environment) {
        Binder binder = Binder.get(environment);
        String issuerUri = text(binder, JWT + ".issuer-uri");
        String jwkSetUri = text(binder, JWT + ".jwk-set-uri");
        String publicKeyLocation = text(binder, JWT + ".public-key-location");
        if (issuerUri.isEmpty() && jwkSetUri.isEmpty() && publicKeyLocation.isEmpty()) {
            return "by the JwtDecoder bean defined in code";
        }
        List<String> audiences = binder.bind(JWT + ".audiences", Bindable.listOf(String.class))
                .orElse(List.of()).stream().filter(StringUtils::hasText).toList();
        if (audiences.isEmpty()) {
            throw new IllegalStateException(JWT + ".audiences must be set when bearer tokens are "
                    + "enabled; without it a token the issuer signed for any other client is accepted");
        }
        if (!jwkSetUri.isEmpty() && issuerUri.isEmpty()) {
            throw new IllegalStateException(JWT + ".issuer-uri must be set with " + JWT
                    + ".jwk-set-uri; without it a token signed by any key in that set is accepted, "
                    + "whoever issued it");
        }
        return "aud in " + audiences
                + (issuerUri.isEmpty() ? ", key from " + publicKeyLocation : ", iss " + issuerUri);
    }

    private static String text(Binder binder, String name) {
        return binder.bind(name, String.class).map(String::strip).orElse("");
    }

    /**
     * Dispatches on the {@code {id}} prefix of each stored value, so hashes from two
     * algorithms verify side by side during a rotation. {@code {argon2}} and
     * {@code {scrypt}} also need {@code org.bouncycastle:bcprov-jdk18on}, which this
     * build does not include.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * Two identities in memory. The callers are a service and a scraper, and a users
     * table would add a query to every request to hold two rows the configuration
     * already has. The {@code api} user holds the same authorities a JWT's scopes map to.
     */
    @Bean
    UserDetailsService userDetailsService(ApiSecurityProperties properties, PasswordEncoder encoder,
                                          Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("prod"))) {
            refuseUnhashed("app.security.api-password", "API_PASSWORD", properties.apiPassword());
            refuseUnhashed("app.security.ops-password", "OPS_PASSWORD", properties.opsPassword());
        }
        assertVerifiable(encoder, "app.security.api-password", properties.apiPassword());
        assertVerifiable(encoder, "app.security.ops-password", properties.opsPassword());
        return new InMemoryUserDetailsManager(
                User.withUsername("api")
                        .password(properties.apiPassword())
                        .authorities(SCOPE_READ, SCOPE_WRITE)
                        .build(),
                User.withUsername("ops")
                        .password(properties.opsPassword())
                        // roles(), not authorities(): roles() adds the ROLE_ prefix
                        // that hasRole() checks for.
                        .roles(ROLE_OPS)
                        .build());
    }

    /**
     * Under {@code prod}, refuses {@code {noop}}: the stored value is then the password
     * itself, so anyone who can read the Secret or the pod's environment can log in. The
     * default profile keeps its {@code {noop}} values for localhost. A case-sensitive
     * match is enough, because {@code {NOOP}} is an unknown id that
     * {@link #assertVerifiable} refuses. Like the prefix check in
     * {@link ApiSecurityProperties}, it names the property and never the value.
     */
    private static void refuseUnhashed(String property, String variable, String encoded) {
        if (encoded.startsWith("{noop}")) {
            throw new IllegalStateException(property + " (" + variable + ") must be a hashed password "
                    + "under the prod profile, for example {bcrypt}$2y$10$...; {noop} is refused, "
                    + "and the value is not shown");
        }
    }

    /**
     * Stops startup when the encoder cannot verify a value at all, which would otherwise
     * fail every authenticated request with a 500. An unknown {@code {id}} throws
     * {@code IllegalArgumentException}; argon2 or scrypt without BouncyCastle throws
     * {@code NoClassDefFoundError}, hence the {@code LinkageError}. A mismatch is fine.
     */
    private static void assertVerifiable(PasswordEncoder encoder, String property, String encoded) {
        try {
            encoder.matches("startup-self-check", encoded);
        } catch (RuntimeException | LinkageError e) {
            throw new IllegalStateException(property
                    + " cannot be verified by the configured DelegatingPasswordEncoder: " + e, e);
        }
    }
}
