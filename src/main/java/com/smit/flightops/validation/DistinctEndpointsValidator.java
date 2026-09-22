package com.smit.flightops.validation;

import com.smit.flightops.dto.CreateFlightRequest;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Locale;

/**
 * Implements {@link DistinctEndpoints}.
 *
 * <p>Two details here are the whole reason this class is worth reading.
 *
 * <p><b>It reports against the {@code destination} node.</b> A class-level
 * constraint's violation is by default a *global* error, not a field error, and
 * {@code GlobalExceptionHandler} builds its {@code fieldErrors} map from
 * {@code getFieldErrors()} — so the natural implementation produces a 400 whose
 * {@code fieldErrors} object is empty. The client is told the request is
 * invalid and nothing else. Re-targeting the violation at a property node with
 * {@code addPropertyNode} puts it back in the map where a client can attach it
 * to an input. (The handler also reports global errors now, as a backstop, but
 * a cross-field error pointed at the field the user should change is the better
 * answer.)
 *
 * <p><b>It normalises before comparing.</b> {@code FlightService} upper-cases
 * and trims both codes before persisting, so {@code ewr} and {@code EWR} are
 * the same airport by the time the row is written. Comparing the raw strings
 * would accept {@code origin=ewr, destination=EWR} here and then hit
 * {@code ck_flights_distinct_endpoints} in the database, turning a clean 400
 * into a 409 with a constraint name in it.
 *
 * <p>Nulls and blanks are passed as valid: {@code @NotBlank} on the fields owns
 * that failure, and a validator that also reported it would give the client two
 * messages for one mistake.
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
