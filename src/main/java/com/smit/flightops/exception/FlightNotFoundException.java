package com.smit.flightops.exception;

public class FlightNotFoundException extends RuntimeException {

    private final String flightNumber;

    public FlightNotFoundException(String flightNumber) {
        super("Flight not found: " + flightNumber);
        this.flightNumber = flightNumber;
    }

    public String getFlightNumber() { return flightNumber; }
}
