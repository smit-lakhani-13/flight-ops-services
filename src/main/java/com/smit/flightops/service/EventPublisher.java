package com.smit.flightops.service;

import com.smit.flightops.dto.BookingDto;

/**
 * Seam between the booking transaction and whatever transport carries the
 * event — SQS here, Solace or Kafka elsewhere. The service depends on this
 * interface, so swapping transports touches one bean definition, not the
 * business logic.
 */
public interface EventPublisher {

    void publishBookingCreated(BookingDto booking);
}
