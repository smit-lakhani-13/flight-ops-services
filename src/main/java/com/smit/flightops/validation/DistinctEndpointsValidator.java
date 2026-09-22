package com.smit.flightops.validation;

import com.smit.flightops.dto.CreateFlightRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Locale;

/**
 * Implements {@link DistinctEndpoints}.
 *
 * <p>The violation is re-targeted at {@code destination}. A class-level
 * violation is otherwise a global error, which {@code GlobalExceptionHandler}
 * can only key by the object name, not by a field a client can highlight.
 *
 * <p>Both codes are trimmed and upper-cased first, as {@code FlightService}
 * does, so {@code ewr}/{@code EWR} is a 400 here and not a 409 from
 * {@code ck_flights_distinct_endpoints}. Nulls and blanks pass, because
 * {@code @NotBlank} already reports them.
 */
public class DistinctEndpointsValidator
        implements ConstraintValidator<DistinctEndpoints, CreateFlightRequest> {

    @Override
    public boolean isValid(CreateFlightRequest request, ConstraintValidatorContext context) {
        if (request == null) {
            return true;
        }
        String origin = normalise(request.origin());
        String destination = normalise(request.destination());
        if (origin == null || destination == null || !origin.equals(destination)) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                        context.getDefaultConstraintMessageTemplate())
                .addPropertyNode("destination")
                .addConstraintViolation();
        return false;
    }

    private static String normalise(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }
}
