package com.smit.flightops;

import com.smit.flightops.entity.FlightStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published API document, checked against the service it describes. The
 * failure worth testing for is drift, a document that still renders but
 * describes behaviour the API no longer has, so the page-shape test compares
 * the schema with a real response.
 *
 * <p>A full context with the real filter chain: the document is public and the
 * endpoints are not, and a slice would apply Boot's default security rules.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:openapitest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class OpenApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Each operation's documented status codes. Every endpoint needs credentials
     * and a scope. Every endpoint opens a transaction, so each of them can answer
     * 503 when the database is out of reach, and every write to an existing
     * flight row can also wait behind a booking's row lock. Every write with a
     * body reads JSON only, so each of them can answer 415.
     */
    private static final Map<String, List<String>> RESPONSES = Map.of(
            "get /api/v1/flights", List.of("200", "400", "401", "403", "503"),
            "post /api/v1/flights", List.of("201", "400", "401", "403", "409", "415", "503"),
            "get /api/v1/flights/{flightNumber}", List.of("200", "401", "403", "404", "503"),
            "delete /api/v1/flights/{flightNumber}", List.of("204", "401", "403", "404", "409", "503"),
            "patch /api/v1/flights/{flightNumber}/status", List.of("200", "400", "401", "403", "404", "409", "415", "503"),
            "get /api/v1/bookings", List.of("200", "400", "401", "403", "503"),
            "post /api/v1/bookings", List.of("201", "400", "401", "403", "404", "409", "415", "503"),
            "get /api/v1/bookings/{bookingId}", List.of("200", "400", "401", "403", "404", "503"),
            "delete /api/v1/bookings/{bookingId}", List.of("200", "400", "401", "403", "404", "503"));

    /** The operations that take or queue behind the flight row lock, and so can time out on it. */
    private static final List<String> LOCKING = List.of(
            "delete /api/v1/flights/{flightNumber}",
            "patch /api/v1/flights/{flightNumber}/status",
            "post /api/v1/bookings",
            "delete /api/v1/bookings/{bookingId}");

    @Autowired private MockMvc mockMvc;

    private JsonNode document() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return MAPPER.readTree(body);
    }

    @Test
    @DisplayName("the document is readable without credentials; the endpoints it describes are not")
    void theDocumentIsPublicAndTheApiIsNot() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
        mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());

        // The line that makes the one above safe. A description of an endpoint
        // is not a credential for it.
        mockMvc.perform(get("/api/v1/flights")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/bookings/1")).andExpect(status().isUnauthorized());

        // GET and HEAD only. A write verb on a docs path is not a documented
        // operation, so it falls to anyRequest().denyAll() rather than reaching
        // a handler that does not exist.
        mockMvc.perform(post("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("every operation is documented with the status codes it returns, 401 and 403 included")
    void theDocumentCoversTheApiAndItsFailures() throws Exception {
        JsonNode paths = document().get("paths");

        Map<String, List<String>> documented = new HashMap<>();
        for (String path : paths.propertyNames()) {
            for (String method : paths.get(path).propertyNames()) {
                documented.put(method + " " + path,
                        List.copyOf(paths.get(path).get(method).get("responses").propertyNames()));
            }
        }

        assertThat(documented.keySet()).containsExactlyInAnyOrderElementsOf(RESPONSES.keySet());
        RESPONSES.forEach((operation, codes) ->
                assertThat(documented.get(operation)).as(operation).containsExactlyInAnyOrderElementsOf(codes));
    }

    /**
     * {@code GlobalExceptionHandler} sends both 503 codes with
     * {@code Retry-After}, and {@code RequestIdFilter} puts {@code X-Request-Id}
     * on every response. Only the operations that touch the row lock can time
     * out on it, so only they name {@code LOCK_TIMEOUT}.
     */
    @Test
    @DisplayName("every 503 names the codes its operation can return and declares Retry-After; every response declares X-Request-Id")
    void theSharedResponsesAreDeclaredOnEveryOperation() throws Exception {
        JsonNode document = document();
        JsonNode paths = document.get("paths");

        for (String path : paths.propertyNames()) {
            for (String method : paths.get(path).propertyNames()) {
                String operation = method + " " + path;
                JsonNode responses = paths.get(path).get(method).get("responses");

                JsonNode unavailable = responses.get("503");
                assertThat(unavailable.get("description").asString()).as(operation)
                        .contains("DATABASE_UNAVAILABLE");
                assertThat(unavailable.get("description").asString().contains("LOCK_TIMEOUT")).as(operation)
                        .isEqualTo(LOCKING.contains(operation));
                assertThat(unavailable.get("headers").get("Retry-After").get("$ref").asString()).as(operation)
                        .isEqualTo("#/components/headers/Retry-After");
                assertThat(unavailable.get("content").get("application/json").get("schema").get("$ref").asString())
                        .as(operation).endsWith("/ErrorResponse");

                for (String code : responses.propertyNames()) {
                    assertThat(responses.get(code).get("headers").get("X-Request-Id").get("$ref").asString())
                            .as(operation + " " + code).isEqualTo("#/components/headers/X-Request-Id");
                }
            }
        }

        JsonNode headers = document.get("components").get("headers");
        assertThat(headers.get("Retry-After").get("schema").get("type").asString()).isEqualTo("integer");
        assertThat(headers.get("X-Request-Id").get("schema").get("type").asString()).isEqualTo("string");
    }

    /**
     * Without {@code @ParameterObject} springdoc publishes the {@code Pageable}
     * as one required object parameter named {@code pageable}, and Swagger UI
     * fills it with {@code sort=string}, which the endpoint refuses.
     */
    @Test
    @DisplayName("both list endpoints publish page, size and sort as query parameters, with their defaults")
    void theListEndpointsPublishPageSizeAndSort() throws Exception {
        JsonNode paths = document().get("paths");

        Map<String, String> defaultSort = Map.of(
                "/api/v1/flights", "departureTime,ASC",
                "/api/v1/bookings", "createdAt,ASC");
        defaultSort.forEach((path, sort) -> {
            Map<String, JsonNode> parameters = new HashMap<>();
            paths.get(path).get("get").get("parameters").forEach(p -> parameters.put(p.get("name").asString(), p));

            assertThat(parameters).as(path).containsKeys("page", "size", "sort").doesNotContainKey("pageable");
            assertThat(parameters.values()).as(path).allMatch(p -> p.get("in").asString().equals("query"));
            assertThat(parameters.get("size").get("schema").get("default").asInt()).as(path).isEqualTo(20);
            assertThat(parameters.get("sort").get("schema").get("default").get(0).asString()).as(path).isEqualTo(sort);
        });
    }

    /**
     * Jackson writes {@code "cancelledAt": null} for an active booking, which
     * a schema without a null type refuses. The status is one enum, the same
     * one the status change reads, whatever Java type each side holds it in.
     */
    @Test
    @DisplayName("a null cancelledAt is allowed, and the flight status is one named enum both ways")
    void theResponseSchemasMatchTheWire() throws Exception {
        JsonNode schemas = document().get("components").get("schemas");

        JsonNode cancelledAt = schemas.get("BookingDto").get("properties").get("cancelledAt");
        assertThat(cancelledAt.get("type").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("string", "null");
        assertThat(cancelledAt.get("format").asString()).isEqualTo("date-time");

        assertThat(schemas.get("FlightStatus").get("enum").valueStream().map(JsonNode::asString).toList())
                .containsExactlyElementsOf(Arrays.stream(FlightStatus.values()).map(Enum::name).toList());
        assertThat(schemas.get("FlightDto").get("properties").get("status").get("$ref").asString())
                .isEqualTo("#/components/schemas/FlightStatus");
        assertThat(schemas.get("StatusUpdate").get("properties").get("status").get("$ref").asString())
                .isEqualTo("#/components/schemas/FlightStatus");
    }

    /**
     * Booking's 409s need telling apart: {@code INSUFFICIENT_SEATS} is worth a
     * retry with fewer seats and {@code FLIGHT_NOT_BOOKABLE} never is.
     */
    @Test
    @DisplayName("the booking operation names each error code a client has to tell apart")
    void theBookingFailuresAreDescribedByCode() throws Exception {
        JsonNode book = document().get("paths").get("/api/v1/bookings").get("post").get("responses");

        assertThat(book.get("201").get("headers").has("Location")).isTrue();
        assertThat(book.get("400").get("description").asString())
                .contains("VALIDATION_FAILED")
                .contains("MALFORMED_REQUEST");
        assertThat(book.get("409").get("description").asString())
                .contains("INSUFFICIENT_SEATS")
                .contains("FLIGHT_NOT_BOOKABLE")
                .contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(book.get("503").get("description").asString()).contains("Retry-After");
    }

    @Test
    @DisplayName("creating a flight is documented as 201 with a Location header and the flight as its body")
    void flightCreationIsDocumentedAsCreated() throws Exception {
        JsonNode create = document().get("paths").get("/api/v1/flights").get("post").get("responses");

        JsonNode created = create.get("201");
        assertThat(created.get("headers").has("Location")).isTrue();
        assertThat(created.get("content").get("application/json").get("schema").get("$ref").asString())
                .endsWith("/FlightDto");
        assertThat(create.get("409").get("description").asString())
                .contains("DUPLICATE_FLIGHT")
                .contains("DUPLICATE_REQUEST");
    }

    /**
     * swagger-core marks a primitive {@code int} optional unless told otherwise,
     * and the server answers a missing count with 400 {@code MALFORMED_REQUEST}.
     */
    @Test
    @DisplayName("a seat count the server refuses when missing is required in the schema")
    void seatCountsAreRequired() throws Exception {
        JsonNode schemas = document().get("components").get("schemas");

        assertThat(schemas.get("BookingRequest").get("required").toString()).contains("\"seats\"");
        assertThat(schemas.get("CreateFlightRequest").get("required").toString()).contains("\"totalSeats\"");
    }

    /**
     * swagger-core publishes a lone {@code @Pattern} and drops repeated ones, so
     * the passenger name's character rule is restated in {@code @Schema}.
     */
    @Test
    @DisplayName("the passenger name's character rule is in the schema")
    void thePassengerNameCharacterRuleIsDocumented() throws Exception {
        JsonNode passengerName = document().get("components").get("schemas")
                .get("BookingRequest").get("properties").get("passengerName");

        assertThat(passengerName.get("pattern").asString()).isEqualTo("^[^\\p{Cc}\\p{Cs}]*$");
    }

    /**
     * Compared with a live response, not a written expectation, because Spring
     * Data's page shape has changed between versions.
     */
    @Test
    @DisplayName("the documented page shape is the shape the API returns")
    void theDocumentedPageShapeIsTheShapeTheApiReturns() throws Exception {
        JsonNode schema = document().get("components").get("schemas").get("PagedModelFlightDto");
        List<String> documented = schema.get("properties").propertyNames().stream().sorted().toList();

        String response = mockMvc.perform(get("/api/v1/flights").with(httpBasic("api", "dev-secret")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> actual = MAPPER.readTree(response).propertyNames().stream().sorted().toList();

        assertThat(actual).isEqualTo(documented);
    }

    @Test
    @DisplayName("the document declares the authentication the service actually accepts")
    void theSecurityRequirementIsDocumentedOnceAndAppliesToEverything() throws Exception {
        JsonNode document = document();

        assertThat(document.get("openapi").asString()).startsWith("3.1");
        assertThat(document.get("info").get("license").get("name").asString()).isEqualTo("MIT");

        JsonNode schemes = document.get("components").get("securitySchemes");
        assertThat(schemes.propertyNames()).containsExactly("basicAuth");
        assertThat(schemes.get("basicAuth").get("scheme").asString()).isEqualTo("basic");

        // Declared once for the document, so a new endpoint is documented as
        // needing credentials. Bearer is absent: it works only when an issuer
        // is configured, and this instance would reject it.
        assertThat(document.get("security").get(0).propertyNames()).containsExactly("basicAuth");
        assertThat(schemes.propertyNames()).doesNotContain("bearerAuth");
    }
}
