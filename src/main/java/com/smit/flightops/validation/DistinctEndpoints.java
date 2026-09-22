package com.smit.flightops.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

/**
 * A flight's origin and destination must differ.
 *
 * <p>Class-level, because neither field is wrong on its own; only the pair is.
 * {@code ck_flights_distinct_endpoints} (V2) is the guarantee for every writer.
 * This constraint makes the API answer 400 with a field error before the
 * database would answer 409 with a constraint name.
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
