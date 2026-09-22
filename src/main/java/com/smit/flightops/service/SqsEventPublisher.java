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
    public void publish(String eventType, String payload) {
        // The type travels as a message attribute rather than as a field
        // inside the body. A consumer, an SNS filter policy or an EventBridge
        // rule can then route on it without deserialising - and, more to the
        // point, without this service and that consumer having to agree on the
        // body's schema just so one of them can work out what it is holding.
        SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(payload)
                .messageAttributes(Map.of("eventType", MessageAttributeValue.builder()
                        .dataType("String")
                        .stringValue(eventType)
                        .build()))
                .build());

        log.info("Published {} to SQS (messageId={})", eventType, response.messageId());
    }
}
