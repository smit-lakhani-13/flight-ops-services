package com.smit.flightops.dto;

import com.smit.flightops.entity.Flight;
import com.smit.flightops.entity.FlightStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * What a caller gets back for a flight.
 *
 * @param status the name of a {@link FlightStatus}. {@code @Schema} publishes
 *        it as the {@code FlightStatus} enum component that {@link StatusUpdate}
 *        reads too, so a generated client gets one type for both and can switch
 *        on it. The component stays a {@code String}, so the JSON is unchanged
 */
public record FlightDto(String flightNumber, String origin, String destination,
                        int totalSeats, int availableSeats,
                        @Schema(implementation = FlightStatus.class, enumAsRef = true) String status,
                        Instant departureTime) {

    public static FlightDto from(Flight f) {
        return new FlightDto(f.getFlightNumber(), f.getOrigin(), f.getDestination(),
                             f.getTotalSeats(), f.getAvailableSeats(),
                             f.getStatus().name(), f.getDepartureTime());
    }
}
