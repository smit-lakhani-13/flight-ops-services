package com.smit.flightops.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * @see "adr/0011-correlation-ids-and-metrics.md"
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** The MDC key. Referenced by {@code logging.pattern.correlation}. */
    public static final String MDC_KEY = "requestId";

    /** No whitespace or control characters; 128 bounds a caller that sends a whole document. */
    private static final Pattern ACCEPTABLE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = sanitise(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);

        // Before the chain: once the response is committed, setHeader is a no-op.
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Cleared so no later line on this thread carries the id. The /error
            // dispatch runs after this, so ApiErrorController reads the id back from
            // the response header for the line it logs.
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitise(String candidate) {
        return candidate != null && ACCEPTABLE.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
