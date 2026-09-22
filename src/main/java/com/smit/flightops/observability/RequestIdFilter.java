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
 * Gives every request an id, puts it in the MDC so it appears on each log line,
 * and echoes it back so the caller can quote it.
 *
 * <p><b>What this is for.</b> A support conversation normally starts with "it
 * failed around three o'clock" and ends in a grep across four replicas. With
 * this, it starts with an id the caller read off their own response, and ends
 * in one query. That is the whole feature. The trace id from OpenTelemetry does
 * a related job — it stitches this service to the Lambda downstream — but it is
 * not in the response and a caller has no way to know it, so the two are
 * complementary rather than redundant.
 *
 * <p><b>{@code HIGHEST_PRECEDENCE}, and it is load-bearing.</b> Spring
 * Security's chain rejects unauthenticated requests with 401 before
 * {@code DispatcherServlet} runs. A filter registered after it would never see
 * those requests, so exactly the responses people most often need to
 * investigate — "my credentials stopped working" — would be the ones with no
 * id on them. Running first means the header is on every response the
 * application produces, authenticated or not.
 *
 * <p><b>Why an inbound id is validated rather than trusted.</b> The value goes
 * into a log line and into a response header, so an unvalidated one is two
 * injection sinks at once: a {@code \r\n} splits a log line into a fabricated
 * second entry (or, in the header, forges a response header), and a megabyte of
 * text makes every log line for that request a megabyte long. The pattern below
 * covers UUIDs, ULIDs, W3C trace ids and the {@code service-1234} shapes clients
 * actually send; anything else is quietly replaced rather than rejected, because
 * a malformed diagnostic header is not a reason to fail somebody's booking.
 *
 * <p><b>Not in the error body.</b> {@code ErrorResponse} is a published
 * contract, and adding a field to it to carry something already present in a
 * header would mean two places to keep in step for no gain.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";

    /** The MDC key. Referenced by {@code logging.pattern.correlation}. */
    public static final String MDC_KEY = "requestId";

    /**
     * Deliberately permissive about alphabet and strict about everything else.
     * No whitespace and no control characters is the part that matters; 128
     * bounds the damage of a caller that sends a whole JSON document.
     */
    private static final Pattern ACCEPTABLE = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String requestId = sanitise(request.getHeader(HEADER));
        MDC.put(MDC_KEY, requestId);

        // Set before the chain runs, not after. Once anything downstream has
        // written enough to commit the response, headers are frozen and this
        // would be a silent no-op -- silent because setHeader does not throw.
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Virtual threads are one per request, so a leaked key would not
            // reach another caller today. It would still reach the container's
            // own shutdown and error-dispatch logging on this thread, and the
            // configuration that makes that true lives in a different file.
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitise(String candidate) {
        return candidate != null && ACCEPTABLE.matcher(candidate).matches()
                ? candidate
                : UUID.randomUUID().toString();
    }
}
