package com.smit.flightops.service;

import com.smit.flightops.config.AwsProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends the payload to SQS, where the Lambda in {@code lambda/} picks it up.
 *
 * <p>Selected by {@code app.events.publisher: sqs} — an explicit mode switch
 * rather than "is a queue URL configured?". A blank-but-present
 * {@code SQS_QUEUE_URL} would satisfy a bare {@code @ConditionalOnProperty},
 * silently activating this bean on a laptop and breaking the local run.
 *
 * <p>This no longer runs inside the booking transaction — {@link OutboxWriter}
 * and {@link OutboxPublisher} are the two halves of that fix. What is left here
 * is a transport and nothing else: it does not build the event, does not
 * serialise it, and cannot change it. If the send throws, the poller records
 * the failure against the row and tries again on the next tick, so a queue
 * outage delays events instead of rejecting bookings.
 */
@Component
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "sqs")
public class SqsEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsEventPublisher.class);

    private final SqsClient sqsClient;
    private final String queueUrl;

    public SqsEventPublisher(SqsClient sqsClient, AwsProperties awsProperties) {
        this.sqsClient = sqsClient;
        this.queueUrl = awsProperties.sqsQueueUrl();
        if (queueUrl == null || queueUrl.isBlank()) {
            // Fail at startup, not on the first booking of the day.
            throw new IllegalStateException(
                    "app.events.publisher=sqs requires app.aws.sqs-queue-url (env SQS_QUEUE_URL)");
        }
    }

    @Override
    public void publish(String eventType, String payload, Map<String, String> headers) {
        // The type travels as a message attribute rather than as a field
        // inside the body. A consumer, an SNS filter policy or an EventBridge
        // rule can then route on it without deserialising - and, more to the
        // point, without this service and that consumer having to agree on the
        // body's schema just so one of them can work out what it is holding.
        // traceparent rides along for the same reason: it is metadata about
        // the delivery, not part of the event, and putting it in the body
        // would make a diagnostic field a contract change.
        SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(payload)
                .messageAttributes(attributes(eventType, headers))
                .build());

        log.info("Published {} to SQS (messageId={})", eventType, response.messageId());
    }

    /**
     * SQS allows ten message attributes and rejects the whole request if an
     * attribute value is empty, so blank headers are dropped rather than sent.
     * A rejected send would be indistinguishable, from the poller's side, from
     * a queue outage: same exception, same retry, same row stuck at a rising
     * attempt count - for a diagnostic field that was never important enough
     * to fail a booking event over.
     */
    private static Map<String, MessageAttributeValue> attributes(String eventType,
                                                                 Map<String, String> headers) {
        Map<String, MessageAttributeValue> attributes = new LinkedHashMap<>();
        attributes.put("eventType", stringAttribute(eventType));
        headers.forEach((name, value) -> {
            if (value != null && !value.isBlank()) {
                attributes.put(name, stringAttribute(value));
            }
        });
        return attributes;
    }

    private static MessageAttributeValue stringAttribute(String value) {
        return MessageAttributeValue.builder().dataType("String").stringValue(value).build();
    }
}
