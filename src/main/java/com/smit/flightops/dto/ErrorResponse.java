package com.smit.flightops.dto;

import java.time.Instant;

/**
 * The shape of every error a client can only read and react to:
 * {@code {code, message, timestamp}}. The validation shape is the one exception
 * and lives in {@link ValidationErrorResponse}.
 *
 * <p>The factory takes the instant rather than reading the clock. That looks
 * like ceremony for a timestamp nobody asserts on, and it is not: the callers
 * are {@code GlobalExceptionHandler} and {@code ErrorResponseWriter}, both of
 * which a test drives directly, and a timestamp read from the wall clock inside
 * a static method is the one field such a test can never pin. Passing
 * {@code clock.instant()} in makes it pinnable and leaves exactly one source of
 * time in the application.
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(String code, String message, Instant at) {
        return new ErrorResponse(code, message, at);
    }
}
