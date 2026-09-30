package com.smit.flightops.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorityAuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * 403 for a caller that authenticated and then asked for something no rule grants it.
 * 401 would send a well-behaved client into a credential refresh that cannot help.
 * The WARN names the method and path, never the principal or a header, so the log does
 * not become a second home for a credential.
 *
 * <p>A bearer token refused by a rule that asks for one scope also gets RFC 6750's
 * {@code insufficient_scope} challenge, naming that scope, so an OAuth client can ask
 * for a token that carries it. This handler replaces the resource server's own, which
 * would send the challenge on every bearer 403, including the ones no token can lift.
 *
 * @see com.smit.flightops.config.SecurityConfig
 */
@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(JsonAccessDeniedHandler.class);

    /** The prefix the resource server's converter puts on each scope in a token. */
    private static final String SCOPE_PREFIX = "SCOPE_";

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
        String scope = missingScope(accessDeniedException);
        Authentication caller = SecurityContextHolder.getContextHolderStrategy().getContext().getAuthentication();
        if (scope != null && caller instanceof AbstractOAuth2TokenAuthenticationToken<?>) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer " + JsonAuthenticationEntryPoint.REALM
                    + ", error=\"insufficient_scope\", scope=\"" + scope + "\"");
        }
        writer.write(response, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "Your credentials do not grant access to this resource");
    }

    /**
     * The scope the refusing rule asked for, read from the decision the rule itself
     * returned, so this class keeps no copy of the rule set. {@code hasAuthority} reports
     * the authority it checked. {@code denyAll()}, which a {@code PUT} meets, reports
     * none, and the ops role is not a scope, so neither 403 names one: no token lifts
     * them.
     */
    static String missingScope(AccessDeniedException denied) {
        if (denied instanceof AuthorizationDeniedException authorization
                && authorization.getAuthorizationResult() instanceof AuthorityAuthorizationDecision decision) {
            List<String> scopes = decision.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .filter(authority -> authority != null && authority.startsWith(SCOPE_PREFIX))
                    .map(authority -> authority.substring(SCOPE_PREFIX.length()))
                    .toList();
            return scopes.size() == 1 ? scopes.getFirst() : null;
        }
        return null;
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
     *
     * <p>Public because {@code RequestIdFilter} writes the same path on its line for
     * every answer of 400 or above outside {@code /actuator/}.
     */
    public static String printable(String uri) {
        return uri == null ? "null" : uri.replaceAll("[^\\x21-\\x7E]", "?");
    }
}
