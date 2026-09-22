package com.smit.flightops.service;

import java.util.Map;

/**
 * Seam between the outbox and whatever transport carries the event — SQS here,
 * Solace or Kafka elsewhere. One bean definition changes to swap transports;
 * no business logic does.
 *
 * <p>It takes a payload rather than a {@code BookingDto}, and that is a
 * deliberate narrowing made when the outbox was introduced. Before, this
 * interface took the booking and each implementation built the event and
 * serialised it, which meant every future transport had to reproduce the wire
 * format correctly and any one of them could quietly get it wrong. Now the
 * event is built and serialised exactly once, in {@link OutboxWriter}, inside
 * the booking's own transaction; an implementation of this interface receives
 * bytes it must not interpret and cannot alter. A transport that cannot change
 * the message is a transport that cannot corrupt it.
 *
 * <p><b>Implementations must be idempotent from the caller's point of view or
 * tolerate redelivery</b>, because the outbox poller can send the same payload
 * twice: it publishes, then marks the row published, and a crash between those
 * two steps leaves the row unmarked. That is at-least-once by construction and
 * it is not a defect to be fixed here — the alternative, marking first, loses
 * events instead, which is strictly worse. The consumer absorbs it.
 */
public interface EventPublisher {

    /**
     * @param eventType the logical event name, e.g. {@code BookingCreated} —
     *                  carried as transport metadata so a consumer can route or
     *                  filter without parsing the body
     * @param payload   the exact serialised event; implementations send it
     *                  unchanged
     * @param headers   transport metadata to send alongside the body. Today it
     *                  holds {@code traceparent} and nothing else. A map rather
     *                  than a {@code String traceparent} parameter because the
     *                  next piece of metadata would otherwise be a second
     *                  breaking change to every implementation, and this
     *                  interface exists to be implemented by transports nobody
     *                  has written yet. Never null; empty is normal, because a
     *                  booking made outside a traced request has no trace
     *                  context and inventing one would put a fabricated id on
     *                  the queue.
     * @throws RuntimeException if the transport rejects the message; the caller
     *                          records the failure and retries the row later
     */
    void publish(String eventType, String payload, Map<String, String> headers);
}
