package com.smit.flightops.service;

import java.util.Map;

/**
 * The seam between the outbox and the transport (SQS here). It takes the payload
 * that {@link OutboxWriter} serialised once, and an implementation sends those
 * bytes unchanged, so no transport can alter the wire format.
 *
 * <p>Delivery is at-least-once: the poller publishes and then marks the row, so a
 * crash in between sends the payload again. The consumer absorbs duplicates.
 */
public interface EventPublisher {

    /**
     * @param eventType the logical event name, e.g. {@code BookingCreated}, sent as
     *                  metadata so a consumer can route without parsing the body
     * @param payload   the serialised event; sent unchanged
     * @param headers   transport metadata, today only {@code traceparent}. A map so the
     *                  next field is not a breaking change. Never null; empty when the
     *                  booking was made outside a traced request
     * @throws RuntimeException if the transport rejects the message; the caller
     *                          records the failure and retries the row later
     */
    void publish(String eventType, String payload, Map<String, String> headers);
}
