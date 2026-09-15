package com.smit.flightops.dto;

import com.smit.flightops.entity.FlightStatus;
import jakarta.validation.constraints.NotNull;

public record StatusUpdate(@NotNull FlightStatus status) {}
