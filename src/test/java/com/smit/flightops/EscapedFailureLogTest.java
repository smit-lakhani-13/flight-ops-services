package com.smit.flightops;

import com.smit.flightops.observability.RequestIdFilter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.catalina.Context;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ServerHttpObservationFilter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An exception that escapes the filter chain, in a running Tomcat. MockMvc never
 * forwards to {@code /error}, and {@code RequestIdFilterTest} has no
 * {@code ServerHttpObservationFilter}, the Spring filter that sets 500 on the response
 * before it rethrows, so neither shows what the running application logs. Here
 * RequestIdFilter must write no line, although the status reads 500 when it finishes,
 * and ApiErrorController's ERROR line must be the one line under the request id.
 *
 * <p>Its own H2 database, because the context seeds demo data and other contexts count
 * rows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
                properties = "spring.datasource.url=jdbc:h2:mem:escapedfailurelogtest;DB_CLOSE_DELAY=-1")
@Import(EscapedFailureLogTest.FailingFilterConfig.class)
@ExtendWith(OutputCaptureExtension.class)
class EscapedFailureLogTest {

    private static final String PATH = "/test/escaped-failure";
    private static final String REQUEST_ID = "escaped-failure-1";

    @TestConfiguration
    static class FailingFilterConfig {

        @Bean
        FailingFilter failingFilter() {
            return new FailingFilter();
        }

        /** Ahead of Spring Security, so the request needs no credentials to reach it. */
        @Bean
        FilterRegistrationBean<FailingFilter> failingFilterRegistration(FailingFilter filter) {
            FilterRegistrationBean<FailingFilter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns(PATH);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            return registration;
        }
    }

    /** Throws on every request, after noting whether the request's observation is open. */
    static final class FailingFilter implements Filter {

        volatile boolean insideObservation;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
            insideObservation = ServerHttpObservationFilter
                    .findObservationContext((HttpServletRequest) request).isPresent();
            throw new IllegalStateException("filter blew up");
        }
    }

    @LocalServerPort private int port;
    @Autowired private WebServerApplicationContext context;
    @Autowired private MeterRegistry meterRegistry;
    @Autowired private FailingFilter failingFilter;

    /** The filters in the order Tomcat runs them, read from the running container. */
    private List<String> filterClassesInChainOrder() {
        Context webapp = (Context) ((TomcatWebServer) context.getWebServer())
                .getTomcat().getHost().findChildren()[0];
        return Arrays.stream(webapp.findFilterMaps())
                .map(map -> webapp.findFilterDef(map.getFilterName()).getFilterClass())
                .toList();
    }

    @Test
    @DisplayName("an exception that escapes the chain is logged by ApiErrorController, not RequestIdFilter")
    void anEscapedFailureIsLoggedOnceByApiErrorController(CapturedOutput output) throws Exception {
        // Checked, not assumed: the observation filter runs inside RequestIdFilter and
        // around the failing filter, so its 500 is on the response when RequestIdFilter
        // finishes.
        assertThat(filterClassesInChainOrder())
                .containsSubsequence(RequestIdFilter.class.getName(),
                                     ServerHttpObservationFilter.class.getName(),
                                     FailingFilter.class.getName());

        HttpResponse<String> response;
        try (HttpClient client = HttpClient.newHttpClient()) {
            response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + PATH))
                                           .header(RequestIdFilter.HEADER, REQUEST_ID)
                                           .build(),
                                   HttpResponse.BodyHandlers.ofString());
        }

        assertThat(failingFilter.insideObservation).as("the observation filter wrapped this request").isTrue();
        // What the observation filter recorded on its way out: the exception, and the
        // 500 it set on the response before rethrowing.
        assertThat(meterRegistry.get("http.server.requests")
                .tags("status", "500", "exception", "IllegalStateException")
                .timer().count())
                .isEqualTo(1);
        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).hasValue(REQUEST_ID);

        List<String> lines = output.getAll().lines()
                .filter(line -> line.contains("," + REQUEST_ID + "] "))
                .toList();
        assertThat(lines)
                .as("RequestIdFilter writes no line, although the status read 500")
                .noneMatch(line -> line.contains("RequestIdFilter"));
        assertThat(lines).singleElement().satisfies(line -> assertThat(line)
                .contains("ERROR", "ApiErrorController", "Unhandled failure on GET " + PATH));
    }
}
