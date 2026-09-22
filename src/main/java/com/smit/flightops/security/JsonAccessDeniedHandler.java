package com.smit.flightops.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 403 for a request that authenticated successfully and then asked for
 * something its authorities do not cover.
 *
 * <p>The 401/403 split is not cosmetic and it is worth being able to state
 * precisely: 401 means "I do not know who you are", 403 means "I know exactly
 * who you are and the answer is still no". A client can act on the first by
 * presenting credentials; the second will keep failing however many times it
 * retries, so returning 401 there would send a well-behaved client into a
 * credential-refresh loop that can never succeed. Spring Security's {@code
 * ExceptionTranslationFilter} routes to the entry point or to this handler on
 * exactly that distinction — whether the current authentication is anonymous.
 *
 * <p>Logged at WARN with the method and path but <b>not</b> the principal or
 * any header. A repeated 403 from one caller is a real operational signal: a
 * client shipped without a scope, or someone is probing. Neither diagnosis
 * needs the credential in the log file, and putting it there would move a
 * secret from the request into storage that is retained for months and read by
 * more people.
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonAccessDeniedHandler.class);

    private final ErrorResponseWriter writer;

    public JsonAccessDeniedHandler(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        log.warn("Denied {} {} — authenticated caller lacks the required authority",
                request.getMethod(), request.getRequestURI());
        writer.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Your credentials do not grant access to this resource");
    }
}
