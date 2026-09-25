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
 * 403 for a caller that authenticated and then asked for something no rule grants it.
 * 401 would send a well-behaved client into a credential refresh that cannot help.
 * The WARN names the method and path, never the principal or a header, so the log does
 * not become a second home for a credential.
 *
 * @see com.smit.flightops.config.SecurityConfig
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
        // Worded for the rule set as well as the credential: a verb with no rule at
        // all, such as PUT, lands here too, and a working credential is not the cause.
        log.warn("Denied {} {} for an authenticated caller: no rule grants this method and path to its authorities",
                request.getMethod(), printable(request.getRequestURI()));
        writer.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Your credentials do not grant access to this resource");
    }

    /**
     * The path as one line of visible ASCII: anything outside {@code !} to {@code ~},
     * which includes CR, LF and every other control character, becomes {@code ?}.
     * Tomcat refuses a raw CR or LF in the request line and the prod profile's ECS
     * encoder escapes both, but the default profile writes plain text, one record per
     * line, and that guarantee belongs to the line that writes it.
     *
     * <p>{@code GlobalExceptionHandler#printable} and the Lambda's helper turn only control,
     * format and line-separator characters into {@code ?}, so an accented name survives.
     * A path is meant to arrive percent-encoded, so visible ASCII loses nothing here,
     * and {@code String.replaceAll} with a negated class is the form CodeQL's
     * log-injection query treats as a sanitiser.
     */
    static String printable(String uri) {
        return uri == null ? "null" : uri.replaceAll("[^\\x21-\\x7E]", "?");
    }
}
