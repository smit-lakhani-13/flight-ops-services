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
 * The parts of the OpenAPI document that cannot be derived from the code.
 *
 * <p>springdoc reads the controllers, the DTO records and the Bean Validation
 * annotations, so the paths, the schemas and most of the constraints arrive
 * without being written twice — which is the point of generating the document
 * rather than maintaining one. What it cannot know is who owns the API, what
 * licence it is under and how a caller is expected to authenticate, so that is
 * all this class contains.
 *
 * <h2>Why the security scheme is declared here and not on each operation</h2>
 * Every endpoint under {@code /api/**} needs credentials — see
 * {@link SecurityConfig} — so a per-operation annotation would be the same
 * annotation on every method, and the one somebody forgets to add is the one
 * that documents an authenticated endpoint as public. A document-level
 * {@code SecurityRequirement} inverts that: the default is "authentication
 * required" and an exception would have to be written deliberately.
 *
 * <p>The scheme is HTTP Basic because that is what the deployment actually
 * uses. Declaring {@code bearerAuth} as well would be aspirational: the JWT
 * half of {@link SecurityConfig} only activates when an issuer is configured,
 * and a document that advertises an authentication method the running instance
 * will reject is worse than one that advertises none.
 */
@Configuration
public class OpenApiConfig {

    private static final String BASIC_AUTH = "basicAuth";

    /**
     * The version comes from {@code BuildProperties} when it is there and falls
     * back when it is not.
     *
     * <p>{@code BuildProperties} exists only if the {@code build-info} goal ran,
     * and it is absent in a {@code @WebMvcTest} slice, which does not load
     * {@code ProjectInfoAutoConfiguration} at all. Injecting it directly would
     * make this class an unsatisfied dependency in exactly the contexts that
     * have nothing to do with it, so it is asked for rather than required.
     */
    @Bean
    public OpenAPI flightOpsOpenApi(ObjectProvider<BuildProperties> buildProperties) {
        String version = buildProperties.getIfAvailable() == null
                ? "1.0.0"
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
                                `flights:write`; this document and the Swagger UI are the \
                                only unauthenticated paths in the service.

                                `POST /api/v1/bookings` is idempotent on `idempotencyKey`: \
                                a repeated request returns the original booking, and the \
                                same key with a different body is rejected rather than \
                                quietly replayed.""")
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
