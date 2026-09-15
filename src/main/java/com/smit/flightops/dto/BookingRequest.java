package com.smit.flightops.dto;

import jakarta.validation.constraints.*;

public record BookingRequest(
    @NotBlank @Size(max = 10)  String flightNumber,
    @NotBlank @Size(max = 255) String passengerName,
    @Min(1) @Max(9)            int seats,
    /** Client-generated. Same key twice = the same booking, never two. */
    @NotBlank @Size(max = 255) String idempotencyKey
) {}
