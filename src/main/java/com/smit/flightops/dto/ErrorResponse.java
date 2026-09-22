package com.smit.flightops.dto;

import java.time.Instant;

/**
 * The {@code {code, message, timestamp}} shape of every error except a
 * validation failure, which is {@link ValidationErrorResponse}.
 *
 * <p>The factory takes the instant instead of reading the clock. The callers
 * are {@code GlobalExceptionHandler}, {@code ApiErrorController} and
 * {@code ErrorResponseWriter}, and passing {@code clock.instant()} keeps every
 * error timestamp on the one injected clock, which a test can pin.
 */
public record ErrorResponse(String code, String message, Instant timestamp) {

    public static ErrorResponse of(String code, String message, Instant at) {
        return new ErrorResponse(code, message, at);
    }
}
