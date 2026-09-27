package com.smit.flightops;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error paths only a running container takes, over HTTP to the embedded Tomcat.
 * MockMvc records a {@code sendError} and stops, so it never forwards to
 * {@code /error}, and its response still takes a header after the body is written.
 * Tomcat's does not, so only a real server shows whether {@code RequestIdFilter} sets
 * the id before the advice, the entry point or the firewall commits the response.
 *
 * <p>Its own H2 database, so it cannot disturb another context's row counts. The server
 * binds the loopback address the test calls. Bound to every address, it can be given a
 * port that another process holds on the loopback address alone, and the requests would
 * reach that process.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:containererrors;DB_CLOSE_DELAY=-1",
        "server.address=127.0.0.1"
})
class ContainerErrorDispatchTest {

    private static final String CLIENT_ERROR = "The request could not be served. Check the method and the path.";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @LocalServerPort private int port;

    @AfterAll
    static void closeClient() {
        CLIENT.close();
    }

    /** Tomcat refuses TRACE before any filter runs, so this 405 comes from ApiErrorController. */
    @Test
    @DisplayName("TRACE is a 405 in the JSON envelope, with the servlet's Allow list")
    void traceIsAJson405() throws Exception {
        HttpResponse<String> response = send("TRACE", "/api/v1/flights");

        assertThat(response.statusCode()).isEqualTo(405);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(JsonPath.<String>read(response.body(), "$.message")).isEqualTo(CLIENT_ERROR);
        assertThat(response.headers().firstValue("Allow")).hasValueSatisfying(
                allow -> assertThat(allow).isNotBlank());
    }

    /**
     * The firewall's 400 is forwarded to /error with no credentials, so it reaches the
     * caller only while /error is permitted; otherwise it becomes a 401.
     */
    @Test
    @DisplayName("a path the firewall refuses is a 400 in the JSON envelope, not a 401")
    void aFirewallRefusalIsAJson400() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/flights;x", "X-Request-Id", "refused-path-1");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("BAD_REQUEST");
        assertThat(JsonPath.<String>read(response.body(), "$.message")).isEqualTo(CLIENT_ERROR);
        assertThat(response.headers().firstValue("X-Request-Id")).hasValue("refused-path-1");
    }

    /**
     * One error from each writer that commits the response early: the advice, the entry
     * point, and the firewall's sendError. A header set after any of them is dropped.
     */
    @Test
    @DisplayName("the caller's X-Request-Id comes back on an advice 404, a 401 and a firewall 400")
    void requestIdOnEveryErrorPath() throws Exception {
        HttpResponse<String> notFound = send("GET", "/api/v1/flights/NOPE1",
                "Authorization", basic("api", "dev-secret"), "X-Request-Id", "advice-404");
        assertThat(notFound.statusCode()).isEqualTo(404);
        assertThat(JsonPath.<String>read(notFound.body(), "$.code")).isEqualTo("FLIGHT_NOT_FOUND");
        assertThat(notFound.headers().firstValue("X-Request-Id")).hasValue("advice-404");

        HttpResponse<String> anonymous = send("GET", "/api/v1/flights/UA123", "X-Request-Id", "anonymous-401");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(anonymous.body(), "$.code")).isEqualTo("UNAUTHENTICATED");
        assertThat(anonymous.headers().firstValue("X-Request-Id")).hasValue("anonymous-401");

        HttpResponse<String> refused = send("GET", "/api/v1/flights;x", "X-Request-Id", "firewall-400");
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.headers().firstValue("X-Request-Id")).hasValue("firewall-400");
    }

    private HttpResponse<String> send(String method, String path, String... headers)
            throws IOException, InterruptedException {
        // Timed out, so a listener that accepts and never answers fails the test
        // instead of hanging the build.
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            request.headers(headers);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Matches the {noop} default in application.yml's default profile. */
    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}
