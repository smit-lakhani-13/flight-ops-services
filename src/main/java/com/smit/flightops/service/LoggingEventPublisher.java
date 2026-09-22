package com.smit.flightops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The default transport: writes the event to the log instead of a queue.
 *
 * <p>This is what makes {@code ./mvnw spring-boot:run} and the whole test suite
 * work with no AWS account, no credentials and no network — the booking path,
 * the outbox and the poller are all exercised exactly as they are in
 * production, and only the last hop differs. A local profile that skipped the
 * outbox entirely would leave the interesting code untested everywhere except
 * production.
 *
 * <p>Selected by {@code app.events.publisher: log}, which is also the default,
 * so forgetting to configure anything gets the safe transport rather than a
 * failed startup or an accidental send.
 */
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "log", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(String eventType, String payload) {
        log.info("[EVENT] {} -> {}", eventType, payload);
    }
}
