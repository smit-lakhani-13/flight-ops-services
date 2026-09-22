package com.smit.flightops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The default transport: writes the event to the log instead of a queue, so a
 * local run and the test suite exercise the booking path, the outbox and the
 * poller with no AWS account. Selected by {@code app.events.publisher: log}, which
 * is also the default when nothing is configured.
 */
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "log", matchIfMissing = true)
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    /** Headers are logged, so every local run exercises trace propagation too. */
    @Override
    public void publish(String eventType, String payload, Map<String, String> headers) {
        log.info("[EVENT] {} {} -> {}", eventType, headers, payload);
    }
}
