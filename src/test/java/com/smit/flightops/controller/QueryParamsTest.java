package com.smit.flightops.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The filter check in isolation. The controller tests show the same refusal
 * reaching the client as 400 {@code MALFORMED_REQUEST}.
 */
class QueryParamsTest {

    /**
     * NUL is the one PostgreSQL refuses. The others are the rest of
     * {@code \p{Cc}}: a C0 control, DEL, and a C1 control, the one Java's
     * {@code \p{Cntrl}} would let through. DEL at the end survives
     * {@code trim()}, which strips only U+0000 to U+0020.
     */
    @ParameterizedTest
    @ValueSource(strings = {"J\u0000K", " J\u0000K ", "J\tK", "J\u007FK", "EWR\u007F", "J\u0085K"})
    @DisplayName("a control character inside a filter, once trimmed, is a 400 that names the parameter")
    void aControlCharacterInsideIsRefused(String value) {
        assertThatThrownBy(() -> QueryParams.withoutControlCharacters("origin", value))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).isEqualTo("origin must not contain control characters.");
                });
    }

    /**
     * The check refuses control characters and nothing else. The service
     * upper-cases {@code ewr} before the query, and a hyphen or a space matches
     * no flight, so the caller gets the empty page the docs promise, as before.
     */
    @ParameterizedTest
    @ValueSource(strings = {"EWR", "ewr", "J-K", "UA 12", "Z\u00FCrich", ""})
    @DisplayName("any other value passes unchanged, whatever its alphabet")
    void anyOtherValuePassesUnchanged(String value) {
        assertThat(QueryParams.withoutControlCharacters("origin", value)).isSameAs(value);
    }

    /** The services trim before they query, so these reach the database without the control character. */
    @ParameterizedTest
    @ValueSource(strings = {"\u0000", "EWR\u0000", "\u0000EWR", "\tEWR\n"})
    @DisplayName("a control character that trim() removes is left to the service's own trim")
    void aControlCharacterAtEitherEndPasses(String value) {
        assertThat(QueryParams.withoutControlCharacters("origin", value)).isSameAs(value);
    }

    @Test
    @DisplayName("an absent optional filter stays absent")
    void nullPasses() {
        assertThat(QueryParams.withoutControlCharacters("destination", null)).isNull();
    }
}
