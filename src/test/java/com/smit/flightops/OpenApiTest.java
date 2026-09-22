package com.smit.flightops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The published API document, checked against the API it describes.
 *
 * <p>Generated documentation has one failure mode worth testing for, and it is
 * not "the file is missing". It is that the document and the service drift
 * apart — the document keeps rendering, keeps looking authoritative, and
 * describes an endpoint that no longer behaves that way. So the load-bearing
 * test here is {@link #theDocumentedPageShapeIsTheShapeTheApiReturns()}, which
 * compares the schema against a real response rather than against an
 * expectation written by the same hand that wrote the schema.
 *
 * <p>The second thing worth pinning is the access rule. Publishing the document
 * anonymously is a deliberate decision and a reasonable one to disagree with;
 * publishing the <em>endpoints</em> anonymously would be a breach. Those two
 * live one line apart in {@code SecurityConfig}, so both are asserted here.
 *
 * <p>Through {@code MockMvc} on a full context with the real filter chain, for
 * the reason {@code SecurityRulesTest} gives: a slice would load Boot's default
 * security rules instead of this application's, and every assertion about who
 * can read what would be about the wrong rule set.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:openapitest;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class OpenApiTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    @DisplayName("every endpoint is described, and every error a caller can provoke is listed")
    void theDocumentCoversTheApiAndItsFailures() throws Exception {
        JsonNode paths = document().get("paths");

        assertThat(paths.propertyNames()).containsExactlyInAnyOrder(
                "/api/v1/flights",
                "/api/v1/flights/{flightNumber}",
                "/api/v1/flights/{flightNumber}/status",
                "/api/v1/bookings",
                "/api/v1/bookings/{bookingId}");

        // Booking is the operation with the most ways to fail and the only one
        // whose 409s mean different things - INSUFFICIENT_SEATS is worth a
        // retry, FLIGHT_NOT_BOOKABLE never is. A client that cannot tell them
        // apart from the document will retry forever.
        JsonNode book = paths.get("/api/v1/bookings").get("post").get("responses");
        assertThat(book.propertyNames())
                .containsExactlyInAnyOrder("201", "400", "401", "403", "404", "409", "503");
        assertThat(book.get("409").get("description").asString())
                .contains("INSUFFICIENT_SEATS")
                .contains("FLIGHT_NOT_BOOKABLE")
                .contains("IDEMPOTENCY_KEY_REUSED");
        assertThat(book.get("503").get("description").asString()).contains("Retry-After");
    }

    /**
     * The document says a list response is {@code {content, page}}. This asks
     * the running service what it actually returns and compares the two, which
     * is the only version of this test that keeps being true.
     *
     * <p>Spring Data's page serialisation has changed shape across versions —
     * the older form spread {@code pageable}, {@code totalElements},
     * {@code last} and {@code first} across the top level — so a schema written
     * by hand, or generated once and trusted, describes whichever shape was
     * current when somebody last looked.
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

        // Declared at the document level rather than per operation, so an
        // endpoint added tomorrow is documented as requiring credentials
        // without anyone remembering to say so. Bearer tokens are deliberately
        // absent: that half only activates when an issuer is configured, and
        // advertising an authentication method the running instance rejects is
        // worse than advertising none.
        assertThat(document.get("security").get(0).propertyNames()).containsExactly("basicAuth");
        assertThat(schemes.propertyNames()).doesNotContain("bearerAuth");
    }
}
