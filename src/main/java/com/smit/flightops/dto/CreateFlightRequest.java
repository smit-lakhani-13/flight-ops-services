package com.smit.flightops.dto;

import jakarta.validation.constraints.*;

import java.time.Instant;

public record CreateFlightRequest(
    @NotBlank @Size(max = 10)                       String flightNumber,
    @NotBlank @Size(min = 3, max = 3)               String origin,
    @NotBlank @Size(min = 3, max = 3)               String destination,
    @Min(1) @Max(850)                               int totalSeats,
    @NotNull @Future                                Instant departureTime
) {}
