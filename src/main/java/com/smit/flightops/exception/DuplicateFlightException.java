package com.smit.flightops.exception;

public class DuplicateFlightException extends RuntimeException {

    private final String flightNumber;

    public DuplicateFlightException(String flightNumber) {
        super("Flight already exists: " + flightNumber);
        this.flightNumber = flightNumber;
    }

    public String getFlightNumber() { return flightNumber; }
}
