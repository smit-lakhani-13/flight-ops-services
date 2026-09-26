package com.smit.flightops.observability;

import com.smit.flightops.security.JsonAccessDeniedHandler;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an id, puts it in the MDC for each log line, and returns it in
 * {@code X-Request-Id} so the caller can quote it. It runs at {@code HIGHEST_PRECEDENCE},
 * ahead of Spring Security, so a 401 or 403 carries an id too. An inbound id is kept only
 * if it matches a short safe alphabet, because it goes into a log line and a response
 * header, where a {@code \r\n} would forge an entry; anything else is replaced, not
 * rejected, since a bad diagnostic header is no reason to fail a request.
 *
 * <p>Every answer of 400 or above also gets one INFO line carrying the id, because
 * most of them log nothing else: a 401 or a 404 would otherwise leave the id a caller
 * quotes pointing at no line at all. A failure that escapes the chain gets no line
 * here, because {@code ApiErrorController} logs it.
 *
 * @see "adr/0011-correlation-ids-and-metrics.md"
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    public static final String HEADER = "X-Request-Id";

    /** The MDC key. Referenced by {@code logging.pattern.correlation}. */
    public static final String MDC_KEY = "requestId";

    /** No whitespace or control characters; 128 bounds a caller that sends a whole document. */
    private static final Pattern ACCEPTABLE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    /** Probes and scrapes: a readiness check that is DOWN would log every period. */
    private static final String ACTUATOR = "/actuator/";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = sanitise(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);

        // Before the chain: once the response is committed, setHeader is a no-op.
        response.setHeader(HEADER, requestId);
        boolean chainThrew = false;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException e) {
            // Noted and rethrown: ApiErrorController logs this one. See logFailedResponse.
            chainThrew = true;
            throw e;
        } finally {
            if (!chainThrew) {
                logFailedResponse(request, response);
            }
            // Cleared so no later line on this thread carries the id. The /error
            // dispatch runs after this, so ApiErrorController reads the id back from
            // the response header for the line it logs.
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * INFO, not WARN: a 4xx is the caller's mistake, and must not trip an alert on
     * warnings. The line names the method, the path and the status. The path goes
     * through the 403 line's rule, {@link JsonAccessDeniedHandler#printable}, and the
     * query string, the headers and the principal stay out, so no credential reaches
     * the log.
     *
     * <p>Not called when an exception escapes the chain, although the status reads 500
     * by then: Spring's {@code ServerHttpObservationFilter}, which Boot registers one
     * step inside this filter, sets 500 on the response before it rethrows. The
     * container then forwards to {@code /error}, and
     * {@code ApiErrorController} logs the failure at ERROR with the same id and the
     * stack trace, so a line here would put the one failure under that id twice.
     */
    private static void logFailedResponse(HttpServletRequest request, HttpServletResponse response) {
        int status = response.getStatus();
        String uri = request.getRequestURI();
        if (status >= 400 && (uri == null || !uri.startsWith(ACTUATOR))) {
            log.info("{} {} -> {}", request.getMethod(), JsonAccessDeniedHandler.printable(uri), status);
        }
    }

    private static String sanitise(String candidate) {
        return candidate != null && ACCEPTABLE.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
