package com.smit.flightops.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 401 for a request that arrived with no usable credentials.
 *
 * <p>This replaces {@code BasicAuthenticationEntryPoint}, whose only real job
 * is the {@code WWW-Authenticate} header, and the header is kept here for the
 * same reason it exists there: it is what tells a client <em>how</em> to
 * authenticate rather than merely that it must. Dropping it would leave
 * {@code curl --user} and every HTTP library's basic-auth retry with nothing
 * to react to.
 *
 * <p>The realm is the application name rather than the framework default
 * {@code "Realm"}, so a developer with several services open can tell from the
 * browser prompt which one is asking.
 *
 * <p>Deliberately says nothing about <em>why</em> the credentials failed. "No
 * such user" and "wrong password" are the same response here, because
 * distinguishing them turns the endpoint into a username oracle — an attacker
 * enumerates valid accounts from the error text alone and never has to guess a
 * password until they know one exists.
 */
@Component
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ErrorResponseWriter writer;

    public JsonAuthenticationEntryPoint(ErrorResponseWriter writer) {
        this.writer = writer;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"flight-ops-service\"");
        writer.write(response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required to access this resource");
    }
}
