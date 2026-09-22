package com.smit.flightops.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * A flight's origin and destination must differ.
 *
 * <p>A class-level constraint rather than a field-level one, because no single
 * field is wrong: {@code EWR} is a perfectly good origin and a perfectly good
 * destination, and only the pair is invalid. Bean Validation calls this a
 * cross-field constraint and this is exactly its shape — the validator receives
 * the whole object.
 *
 * <p>Enforced in three places on purpose, innermost outwards:
 * {@code ck_flights_distinct_endpoints} in V2 so no writer can bypass it, this
 * constraint so the API answers 400 with a field error rather than letting the
 * database answer 409 with a constraint name, and nothing in between. The
 * service does not re-check it; two of these are the guarantee and the third
 * would be the "second caller that forgets".
 */
@Documented
@Constraint(validatedBy = DistinctEndpointsValidator.class)
@Target({ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface DistinctEndpoints {

    String message() default "must differ from origin";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
