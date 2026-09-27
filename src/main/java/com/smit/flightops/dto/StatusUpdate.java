package com.smit.flightops.dto;

import com.smit.flightops.entity.FlightStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/**
 * The body of {@code PATCH /api/v1/flights/{flightNumber}/status}. The enum is
 * published as one named component, the one {@link FlightDto#status()} refers to.
 */
public record StatusUpdate(@NotNull @Schema(enumAsRef = true) FlightStatus status) {}
