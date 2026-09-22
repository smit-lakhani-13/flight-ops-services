package com.smit.flightops.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The parts of the OpenAPI document springdoc cannot derive from the code: the
 * description, the licence and the security scheme. The scheme is declared once for the
 * whole document, so a new operation is documented as needing credentials unless
 * someone writes an exception. It is HTTP Basic only, because the JWT half of
 * {@link SecurityConfig} is off unless an issuer is configured.
 *
 * @see "adr/0012-openapi-public-read.md"
 */
@Configuration
public class OpenApiConfig {

    private static final String BASIC_AUTH = "basicAuth";

    /**
     * The version comes from {@code BuildProperties}, which a {@code @WebMvcTest} slice
     * does not have, so it is optional. The fallback is {@code unknown}, a value that
     * cannot be mistaken for a release.
     */
    @Bean
    public OpenAPI flightOpsOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        String version = buildProperties.getIfAvailable() == null
                ? "unknown"
                : buildProperties.getObject().getVersion();

        return new OpenAPI()
                .info(new Info()
                        .title("Flight Ops Service")
                        .version(version)
                        .description("""
                                Seat inventory and bookings: a small API around one hard \
                                problem, which is not overselling the last seat when two \
                                requests arrive at the same moment.

                                Every endpoint below requires HTTP Basic credentials. \
                                Reads need the `flights:read` scope and writes need \
                                `flights:write`.

                                Nothing under `/api/v1` is reachable without \
                                credentials. Four things outside it are, and all four \
                                have a caller that cannot present any: this document and \
                                the Swagger UI, `/actuator/health` and its liveness and \
                                readiness groups, which a kubelet probes, and `/error`, \
                                which is Spring's internal error dispatch and would turn \
                                every error into a 401 about the error if it were \
                                secured. The rest of `/actuator` needs the `ops` role.

                                `POST /api/v1/bookings` is idempotent on `idempotencyKey`: \
                                a repeated request returns the original booking, and the \
                                same key with a different body is rejected rather than \
                                answered with the original booking.""")
                        .license(new License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")))
                .components(new Components().addSecuritySchemes(BASIC_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("basic")
                        .description("""
                                Two users exist in the default profile: `api` for \
                                `/api/**` and `ops` for the actuator. They come from an \
                                in-memory store, which is a stub - the rules they are \
                                checked against are not.""")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
    }
}
