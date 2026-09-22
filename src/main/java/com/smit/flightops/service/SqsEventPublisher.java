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
 * <p>Selected by {@code app.events.publisher: sqs}, an explicit mode, because a
 * blank {@code SQS_QUEUE_URL} would satisfy a bare {@code @ConditionalOnProperty} on
 * the URL. It runs outside the booking transaction: a failed send is recorded
 * against the outbox row and retried, so a queue outage delays events.
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
        // The type and traceparent travel as message attributes, so a consumer or
        // filter policy can route without parsing the body, and a diagnostic field
        // is not part of the event contract.
        SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(payload)
                .messageAttributes(attributes(eventType, headers))
                .build());

        log.info("Published {} to SQS (messageId={})", eventType, response.messageId());
    }

    /**
     * SQS rejects the whole request if an attribute value is empty (and allows ten
     * attributes), so blank headers are dropped. A rejected send would look like a
     * queue outage and retry for a diagnostic field.
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
