package com.smit.flightops.exception;

public class InsufficientSeatsException extends RuntimeException {

    private final String flightNumber;
    private final int requested;
    private final int available;

    public InsufficientSeatsException(String flightNumber, int requested, int available) {
        super("Flight %s has %d seat(s) available, %d requested"
                  .formatted(flightNumber, available, requested));
        this.flightNumber = flightNumber;
        this.requested = requested;
        this.available = available;
    }

    public String getFlightNumber() { return flightNumber; }
    public int getRequested() { return requested; }
    public int getAvailable() { return available; }
}
