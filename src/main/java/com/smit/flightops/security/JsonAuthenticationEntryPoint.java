package com.smit.flightops.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401 for a request with no usable credentials, with the application's JSON error body.
 * It serves both the Basic filter and the JWT resource server, so the challenge follows
 * the credential that failed: a rejected bearer token gets the RFC 6750 {@code Bearer}
 * challenge, which OAuth clients read to decide whether to refresh, and everything else
 * gets {@code Basic}. The message never says whether the user exists, so the endpoint
 * cannot be used to enumerate accounts.
 *
 * @see com.smit.flightops.config.SecurityConfig
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final String REALM = "realm=\"flight-ops-service\"";

    private final ErrorResponseWriter writer;

    public JsonAuthenticationEntryPoint(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challenge(authException));
        writer.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required to access this resource");
    }

    private static String challenge(AuthenticationException authException) {
        if (authException instanceof OAuth2AuthenticationException bearer) {
            return "Bearer " + REALM + ", error=\"" + bearer.getError().getErrorCode() + "\"";
        }
        return "Basic " + REALM;
    }
}
