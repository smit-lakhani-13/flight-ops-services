package com.smit.flightops.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smit.flightops.dto.BookingCreatedEvent;
import com.smit.flightops.dto.BookingDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Default publisher: serialises the event and logs it instead of sending it.
 *
 * <p>Active whenever {@code app.events.publisher} is {@code log} or absent, so
 * {@code mvn spring-boot:run} needs no AWS credentials, no queue, and no
 * network. It still runs the real Jackson serialisation, so a broken wire
 * contract fails locally rather than in the cloud.
 */
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "log", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    private final ObjectMapper objectMapper;

    public LoggingEventPublisher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void publishBookingCreated(BookingDto booking) {
        BookingCreatedEvent event = BookingCreatedEvent.from(booking);
        try {
            log.info("BookingCreated (not sent — publisher=log): {}",
                     objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // Unreachable for a record of String/int, but silence here would
            // hide exactly the contract break this publisher exists to catch.
            throw new IllegalStateException("Failed to serialise " + event, e);
        }
    }
}
