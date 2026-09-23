package com.smit.flightops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
     * and a scope. Every write to an existing flight row can wait behind a
     * booking's row lock, so each of them can answer 503. Every write with a body
     * reads JSON only, so each of them can answer 415.
     */
    private static final Map<String, List<String>> RESPONSES = Map.of(
            "get /api/v1/flights", List.of("200", "400", "401", "403"),
            "post /api/v1/flights", List.of("201", "400", "401", "403", "409", "415"),
            "get /api/v1/flights/{flightNumber}", List.of("200", "401", "403", "404"),
            "delete /api/v1/flights/{flightNumber}", List.of("204", "401", "403", "404", "409", "503"),
            "patch /api/v1/flights/{flightNumber}/status", List.of("200", "400", "401", "403", "404", "409", "415", "503"),
            "get /api/v1/bookings", List.of("200", "400", "401", "403"),
            "post /api/v1/bookings", List.of("201", "400", "401", "403", "404", "409", "415", "503"),
            "get /api/v1/bookings/{bookingId}", List.of("200", "400", "401", "403", "404"),
            "delete /api/v1/bookings/{bookingId}", List.of("200", "400", "401", "403", "404", "503"));

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
