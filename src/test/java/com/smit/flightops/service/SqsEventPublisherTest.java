package com.smit.flightops.service;

import com.smit.flightops.config.AwsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The message metadata on the wire; {@code BookingEventContractTest} covers the body.
 *
 * <p>SQS rejects the whole {@code SendMessage} if any attribute is empty. A missing
 * traceparent would then look like a queue outage to the poller and end with a dead
 * row, so the publisher drops blank attributes and these tests pin that.
 */
@ExtendWith(MockitoExtension.class)
class SqsEventPublisherTest {

    private static final String QUEUE_URL = "https://sqs.ap-south-1.amazonaws.com/123456789012/booking-events";
    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Mock private SqsClient sqsClient;

    private SendMessageRequest publish(Map<String, String> headers) {
        when(sqsClient.sendMessage(any(SendMessageRequest.class)))
                .thenReturn(SendMessageResponse.builder().messageId("msg-1").build());

        new SqsEventPublisher(sqsClient, new AwsProperties("ap-south-1", QUEUE_URL))
                .publish("BookingCreated", "{\"bookingId\":\"1\"}", headers);

        ArgumentCaptor<SendMessageRequest> request = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqsClient).sendMessage(request.capture());
        return request.getValue();
    }

    @Test
    @DisplayName("the event type and the traceparent travel as message attributes, not in the body")
    void metadataTravelsBesideTheBody() {
        SendMessageRequest request = publish(Map.of("traceparent", TRACEPARENT));

        assertThat(request.queueUrl()).isEqualTo(QUEUE_URL);
        assertThat(request.messageBody())
                .as("the payload is sent as the outbox stored it, unchanged")
                .isEqualTo("{\"bookingId\":\"1\"}");

        Map<String, MessageAttributeValue> attributes = request.messageAttributes();
        assertThat(attributes).containsOnlyKeys("eventType", "traceparent");
        assertThat(attributes.get("eventType").stringValue()).isEqualTo("BookingCreated");
        assertThat(attributes.get("eventType").dataType()).isEqualTo("String");
        assertThat(attributes.get("traceparent").stringValue()).isEqualTo(TRACEPARENT);
    }

    @Test
    @DisplayName("a blank or missing header is dropped rather than sent as an empty attribute")
    void blankHeadersAreDropped() {
        Map<String, String> headers = new HashMap<>();
        headers.put("traceparent", "");
        headers.put("baggage", "   ");
        headers.put("tracestate", null);

        SendMessageRequest request = publish(headers);

        assertThat(request.messageAttributes())
                .as("SQS rejects the whole request over an empty attribute value")
                .containsOnlyKeys("eventType");
    }

    /**
     * The constructor checks the queue URL, so a bad deployment fails at startup. The
     * ConfigMap ships {@code SQS_QUEUE_URL: ""}, which {@code @ConditionalOnProperty}
     * treats as present.
     */
    @Test
    @DisplayName("a blank queue URL fails at startup, not at the first send")
    void aBlankQueueUrlIsRefusedAtStartup() {
        assertThatThrownBy(() -> new SqsEventPublisher(sqsClient,
                new AwsProperties("ap-south-1", "  ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SQS_QUEUE_URL");
    }
}
