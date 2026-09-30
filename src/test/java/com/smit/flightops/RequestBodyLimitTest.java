package com.smit.flightops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request body limit through a real Tomcat. A MockMvc request always reports the
 * length of the content it was given, so it cannot send the body without a
 * {@code Content-Length} that the counting stream exists for. These tests send it
 * over a socket instead.
 *
 * <p>The padded booking bodies are exactly the limit or one byte over it, with the
 * padding inside the JSON object, so a reader has to reach the last byte to finish the
 * object. The form body is a little over the limit.
 * {@code BodyPublishers.ofInputStream} has no length, so the client sends such a body
 * with {@code Transfer-Encoding: chunked}.
 *
 * <p>Its own H2 database, because it books seats and other contexts count rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:requestbodylimit;DB_CLOSE_DELAY=-1")
class RequestBodyLimitTest {

    /** The default {@code app.http.max-body-bytes}. */
    private static final int LIMIT = 16384;

    /** Matches the {noop} default in application.yml's default profile. */
    private static final String API_CREDENTIALS = "Basic " + Base64.getEncoder()
            .encodeToString("api:dev-secret".getBytes(StandardCharsets.US_ASCII));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** HTTP/1.1, because a chunked body is an HTTP/1.1 framing. */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @LocalServerPort private int port;

    /** A valid booking, padded with spaces after the opening brace to exactly {@code length} bytes. */
    private static String booking(String idempotencyKey, int length) {
        String fields = """
                "flightNumber":"UA123","passengerName":"Ada Lovelace","seats":1,"idempotencyKey":"%s"}"""
                .formatted(idempotencyKey);
        return "{" + " ".repeat(length - 1 - fields.length()) + fields;
    }

    private static BodyPublisher declared(String body) {
        return BodyPublishers.ofString(body);
    }

    private static BodyPublisher chunked(String body) {
        return BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body.getBytes(StandardCharsets.US_ASCII)));
    }

    private HttpResponse<String> book(BodyPublisher body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings"))
                        .header("Authorization", API_CREDENTIALS)
                        .header("Content-Type", "application/json")
                        .POST(body)
                        .build(),
                BodyHandlers.ofString());
    }

    private static void assertPayloadTooLarge(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/json");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        assertThat(response.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        assertThat(response.headers().firstValue("X-Frame-Options")).hasValue("DENY");

        JsonNode error = MAPPER.readTree(response.body());
        assertThat(error.get("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(error.get("message").asString()).contains(Integer.toString(LIMIT));
        assertThat(error.has("timestamp")).isTrue();
    }

    @Test
    @DisplayName("a JSON body declaring one byte over the limit is 413 PAYLOAD_TOO_LARGE, in JSON, with X-Request-Id")
    void aDeclaredLengthOverTheLimitIsRefused() throws Exception {
        assertPayloadTooLarge(book(declared(booking("limit-declared-over", LIMIT + 1))));
    }

    @Test
    @DisplayName("a declared length over the limit is 413 before the credentials are checked")
    void aDeclaredLengthOverTheLimitIsRefusedBeforeTheCredentials() throws Exception {
        assertPayloadTooLarge(client.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/bookings"))
                        .header("Content-Type", "application/json")
                        .POST(declared(booking("limit-declared-anonymous", LIMIT + 1)))
                        .build(),
                BodyHandlers.ofString()));
    }

    /**
     * Jackson reports the stream's exception inside
     * {@code HttpMessageNotReadableException}, which without its own mapping would be
     * 400 {@code MALFORMED_REQUEST}.
     */
    @Test
    @DisplayName("a chunked body one byte over the limit is 413 PAYLOAD_TOO_LARGE too, not 400 MALFORMED_REQUEST")
    void aChunkedBodyOverTheLimitIsRefused() throws Exception {
        assertPayloadTooLarge(book(chunked(booking("limit-chunked-over", LIMIT + 1))));
    }

    @Test
    @DisplayName("a normal booking is 201, and so is one at exactly the limit, declared or chunked")
    void bodiesUpToTheLimitAreCreated() throws Exception {
        String normal = """
                {"flightNumber":"UA123","passengerName":"Ada Lovelace","seats":1,"idempotencyKey":"limit-normal"}""";
        assertThat(book(declared(normal)).statusCode()).isEqualTo(201);

        assertThat(book(declared(booking("limit-declared-at", LIMIT))).statusCode()).isEqualTo(201);
        assertThat(book(chunked(booking("limit-chunked-at", LIMIT))).statusCode()).isEqualTo(201);
    }

    /**
     * Form parsing is off, so nothing reads a form-encoded body before Spring Security
     * runs. With {@code FormContentFilter} on, a {@code PUT}, {@code PATCH} or
     * {@code DELETE} body was read in full first, and a bad percent escape in it was a
     * 500 and an ERROR stack trace for a caller with no credentials.
     */
    @Test
    @DisplayName("a form body without credentials is 401, unread, however large or malformed")
    void aFormBodyIsNotReadBeforeTheCredentials() throws Exception {
        assertThat(form("PATCH", "/api/v1/flights/UA123/status", chunked("status=" + "x".repeat(LIMIT))).statusCode())
                .isEqualTo(401);
        for (String method : List.of("PUT", "PATCH", "DELETE")) {
            assertThat(form(method, "/api/v1/flights/UA123", declared("a=%zz")).statusCode())
                    .as(method)
                    .isEqualTo(401);
        }
    }

    private HttpResponse<String> form(String method, String path, BodyPublisher body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .method(method, body)
                        .build(),
                BodyHandlers.ofString());
    }
}
