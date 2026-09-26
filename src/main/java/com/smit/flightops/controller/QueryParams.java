package com.smit.flightops.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.regex.Pattern;

/**
 * What both list endpoints check on a filter before it reaches a query, in one
 * place so they cannot drift apart.
 *
 * <p>A control character inside a filter is refused. PostgreSQL refuses a NUL in
 * a text parameter and H2 accepts it, so {@code ?origin=J%00K} failed on one
 * database and answered an empty page on the other. The body fields refuse the
 * same characters through {@code @Pattern}. The value is checked trimmed,
 * because the services trim before they query, so a NUL at either end never
 * reaches the database. The alphabet is deliberately not narrowed:
 * {@code ?origin=J-K} matches no flight and stays a 200 with an empty page.
 */
final class QueryParams {

    private static final Pattern CONTROL = Pattern.compile("\\p{Cc}");

    private QueryParams() {
    }

    /**
     * @param name  the query parameter, for the message
     * @param value the bound value, null when an optional filter is absent
     * @return {@code value} unchanged, for the service to trim and upper-case
     * @throws ResponseStatusException 400 when {@code value}, trimmed, still
     *         holds a {@code \p{Cc}} character. The message names the parameter
     *         and never echoes the value
     */
    static String withoutControlCharacters(String name, String value) {
        if (value != null && CONTROL.matcher(value.trim()).find()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    name + " must not contain control characters.");
        }
        return value;
    }
}
