package com.smit.flightops.service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.smit.flightops.config.AwsProperties;
import com.smit.flightops.dto.BookingCreatedEvent;
import com.smit.flightops.dto.BookingDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/**
 * Sends the event to SQS, where the Lambda in {@code lambda/} picks it up.
 *
 * <p>Selected by {@code app.events.publisher: sqs} — an explicit mode switch
 * rather than "is a queue URL configured?". A blank-but-present
 * {@code SQS_QUEUE_URL} would satisfy a bare {@code @ConditionalOnProperty},
 * silently activating this bean on a laptop and breaking the local run.
 *
 * <p>Known trade-off: this is called inside the booking transaction. A send
 * failure therefore rolls the booking back — no phantom bookings, but a healthy
 * booking can be rejected because the queue is unavailable. The production fix
 * is a transactional outbox: commit the event to a table in the same
 * transaction and let a separate poller drain it.
 */
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "sqs")
public class SqsEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsEventPublisher.class);

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;
    private final String queueUrl;

    public SqsEventPublisher(SqsClient sqsClient, ObjectMapper objectMapper, AwsProperties awsProperties) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
        this.queueUrl = awsProperties.sqsQueueUrl();
        if (queueUrl == null || queueUrl.isBlank()) {
            // Fail at startup, not on the first booking of the day.
            throw new IllegalStateException(
                    "app.events.publisher=sqs requires app.aws.sqs-queue-url (env SQS_QUEUE_URL)");
        }
    }

    @Override
    public void publishBookingCreated(BookingDto booking) {
        BookingCreatedEvent event = BookingCreatedEvent.from(booking);
        String body;
        try {
            body = objectMapper.writeValueAsString(event);
        } catch (JacksonException e) {
            // Unchecked in Jackson 3, so this is deliberate: a serialisation
            // failure is a bug in the event contract, and it should surface with
            // the event in the message rather than as a bare Jackson stack.
            throw new IllegalStateException("Failed to serialise " + event, e);
        }

        SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build());

        log.info("Published BookingCreated for booking {} to SQS (messageId={})",
                 booking.bookingId(), response.messageId());
    }
}
