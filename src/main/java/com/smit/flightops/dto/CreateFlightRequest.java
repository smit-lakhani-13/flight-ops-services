package com.smit.flightops.dto;

import com.smit.flightops.validation.DistinctEndpoints;
import com.smit.flightops.validation.IsoInstantDeserializer;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/**
 * The body of {@code POST /api/v1/flights}. {@code FlightService} trims and
 * upper-cases the codes after validation, so {@code " ua999 "} is stored as
 * {@code UA999}. The airport codes allow no padding, because {@code @Size}
 * counts it and {@code " JF"} would be stored as a two-letter code.
 *
 * <p>The patterns use {@code *}, not {@code +}, so they accept an empty string
 * and leave that failure to {@code @NotBlank}. {@code totalSeats} is marked
 * required for the OpenAPI document, which treats a primitive as optional;
 * Jackson refuses a missing one.
 */
@DistinctEndpoints
public record CreateFlightRequest(
    @NotBlank @Size(max = 10)
    @Pattern(regexp = CreateFlightRequest.FLIGHT_NUMBER, message = "must contain only letters and digits")
    String flightNumber,

    @NotBlank @Size(min = 3, max = 3)
    @Pattern(regexp = "^[A-Za-z]*$", message = "must contain only letters")
    String origin,

    @NotBlank @Size(min = 3, max = 3)
    @Pattern(regexp = "^[A-Za-z]*$", message = "must contain only letters")
    String destination,

    @Schema(requiredMode = Schema.RequiredMode.REQUIRED) @Min(1) @Max(850) int totalSeats,

    @NotNull @Future @JsonDeserialize(using = IsoInstantDeserializer.class)
    Instant departureTime
) {

    /**
     * Letters and digits, with padding the service trims. The number becomes a
     * path segment in the {@code Location} header, so a space, {@code /} or
     * {@code ?} would name a URL that does not resolve to the flight.
     */
    public static final String FLIGHT_NUMBER = "^\\s*[A-Za-z0-9]*\\s*$";
}
