package com.smit.flightops.config;

import com.smit.flightops.observability.RequestIdFilter;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The parts of the OpenAPI document springdoc cannot derive from the code: the
 * description, the licence and the security scheme. The scheme is declared once for the
 * whole document, so a new operation is documented as needing credentials unless
 * someone writes an exception. It is HTTP Basic only, because the JWT half of
 * {@link SecurityConfig} is off unless an issuer is configured. The 503 and the
 * headers that every operation shares are declared once too, by
 * {@link #sharedResponses()}.
 *
 * @see "adr/0012-openapi-public-read.md"
 */
@Configuration
public class OpenApiConfig {

    private static final String BASIC_AUTH = "basicAuth";

    private static final String RETRY_AFTER = "Retry-After";

    private static final String DATABASE_UNAVAILABLE =
            "`DATABASE_UNAVAILABLE`: the service cannot reach its database. Carries `Retry-After`.";

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
                                readiness groups, which a kubelet probes, and `/error`. \
                                The container forwards an error raised outside Spring MVC \
                                to `/error`. The firewall refuses some URLs before \
                                credentials are read, and a secured `/error` would turn \
                                that 400 into a 401. The rest of `/actuator` needs the \
                                `ops` role.

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

    /**
     * What every operation under {@code /api/} shares and no annotation can see.
     * Declared here for the reason the security requirement is: a new operation
     * gets it without anyone remembering to.
     *
     * <ul>
     *   <li><b>503 {@code DATABASE_UNAVAILABLE}.</b> Every operation opens a
     *       transaction, reads included, and
     *       {@code GlobalExceptionHandler#handleDatabaseUnavailable} answers one
     *       that cannot get a connection. The four writes that take or queue
     *       behind the flight row lock already declare a 503 for
     *       {@code LOCK_TIMEOUT}, and the code is added to its description. The
     *       reads and the flight creation take no row lock, so their 503 never
     *       names {@code LOCK_TIMEOUT}.</li>
     *   <li><b>{@code Retry-After}</b> on every 503, since both codes send it.</li>
     *   <li><b>{@code X-Request-Id}</b> on every response. {@code RequestIdFilter}
     *       sets it before the rest of the chain runs, 401 and 403 included.</li>
     * </ul>
     */
    @Bean
    public OpenApiCustomizer sharedResponses() {
        return openApi -> {
            openApi.getComponents()
                    .addHeaders(RETRY_AFTER, new Header()
                            .description("Seconds to wait before retrying. The service sends 1.")
                            .schema(new IntegerSchema()))
                    .addHeaders(RequestIdFilter.HEADER, new Header()
                            .description("""
                                    The id of this request in the service's logs: the caller's own \
                                    `X-Request-Id` when it is 1 to 128 of `A-Z a-z 0-9 . _ : -`, \
                                    otherwise a new UUID.""")
                            .schema(new StringSchema()));

            openApi.getPaths().forEach((path, item) -> {
                if (!path.startsWith("/api/")) {
                    return;
                }
                for (var operation : item.readOperations()) {
                    ApiResponses responses = operation.getResponses();
                    ApiResponse unavailable = responses.get("503");
                    if (unavailable == null) {
                        responses.addApiResponse("503", new ApiResponse()
                                .description(DATABASE_UNAVAILABLE)
                                .content(new Content().addMediaType(
                                        org.springframework.http.MediaType.APPLICATION_JSON_VALUE,
                                        new MediaType().schema(new Schema<>().$ref("ErrorResponse")))));
                    } else if (!unavailable.getDescription().contains("DATABASE_UNAVAILABLE")) {
                        unavailable.description(unavailable.getDescription() + " " + DATABASE_UNAVAILABLE);
                    }
                    responses.get("503").addHeaderObject(RETRY_AFTER, new Header().$ref(RETRY_AFTER));
                    responses.values().forEach(response -> response.addHeaderObject(
                            RequestIdFilter.HEADER, new Header().$ref(RequestIdFilter.HEADER)));
                }
            });
        };
    }
}
