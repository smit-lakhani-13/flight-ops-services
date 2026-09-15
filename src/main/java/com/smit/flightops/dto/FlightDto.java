package com.smit.flightops.dto;

import com.smit.flightops.entity.Flight;

import java.time.Instant;

public record FlightDto(String flightNumber, String origin, String destination,
                        int totalSeats, int availableSeats, String status, Instant departureTime) {

    public static FlightDto from(Flight f) {
        return new FlightDto(f.getFlightNumber(), f.getOrigin(), f.getDestination(),
                             f.getTotalSeats(), f.getAvailableSeats(),
                             f.getStatus().name(), f.getDepartureTime());
    }
}
