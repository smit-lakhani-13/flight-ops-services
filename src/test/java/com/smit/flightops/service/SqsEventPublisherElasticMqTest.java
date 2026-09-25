package com.smit.flightops.service;

import com.smit.flightops.config.AwsProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SqsEventPublisher} against a real SQS API, served by ElasticMQ.
 *
 * <p>{@link SqsEventPublisherTest} captures the request a mocked client was handed,
 * so it cannot see what the service does with it. Here the message goes over HTTP
 * to a server that validates it, stores it and hands it back, and the SDK checks
 * the MD5 digests of the body and attributes in both directions. The client is
 * built here, not by {@code AwsConfig}, because that one has no endpoint override
 * and reads real credentials. ElasticMQ is an emulator: this is not AWS.
 *
 * <p>{@code disabledWithoutDocker = true}, like
 * {@link com.smit.flightops.BookingIntegrationTest}, so read the skip count. CI runs
 * it, and its "The emulator tests ran" step fails if it skipped.
 */
@Testcontainers(disabledWithoutDocker = true)
class SqsEventPublisherElasticMqTest {

    /**
     * A release tag, not latest, so a new emulator release arrives only in a
     * commit that says so.
     */
    @Container
    static GenericContainer<?> elasticMq =
            new GenericContainer<>(DockerImageName.parse("softwaremill/elasticmq-native:1.7.1"))
                    .withExposedPorts(9324);

    private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    private static final String PAYLOAD = "{\"bookingId\":\"1\",\"flightNumber\":\"UA123\",\"seats\":3,"
            + "\"timestamp\":\"2026-09-15T10:00:00.000000Z\"}";

    private static SqsClient sqsClient() {
        return SqsClient.builder()
                .endpointOverride(URI.create(
                        "http://" + elasticMq.getHost() + ":" + elasticMq.getMappedPort(9324)))
                .region(Region.AP_SOUTH_1)
                // ElasticMQ ignores the signature, but the SDK signs every request and needs keys.
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @Test
    @DisplayName("a published event comes off the queue with its body unchanged and its metadata as attributes")
    void publishedEventRoundTripsThroughTheQueue() {
        try (SqsClient sqs = sqsClient()) {
            String queueUrl = sqs.createQueue(request -> request.queueName("booking-events")).queueUrl();

            new SqsEventPublisher(sqs, new AwsProperties("ap-south-1", queueUrl))
                    .publish("BookingCreated", PAYLOAD, Map.of("traceparent", TRACEPARENT));

            List<Message> messages = sqs.receiveMessage(request -> request
                    .queueUrl(queueUrl)
                    .messageAttributeNames("All")
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(5)).messages();

            assertThat(messages).hasSize(1);
            Message message = messages.getFirst();
            assertThat(message.body())
                    .as("the payload is delivered as the outbox stored it, unchanged")
                    .isEqualTo(PAYLOAD);

            Map<String, MessageAttributeValue> attributes = message.messageAttributes();
            assertThat(attributes).containsOnlyKeys("eventType", "traceparent");
            assertThat(attributes.get("eventType").dataType()).isEqualTo("String");
            assertThat(attributes.get("eventType").stringValue()).isEqualTo("BookingCreated");
            assertThat(attributes.get("traceparent").dataType()).isEqualTo("String");
            assertThat(attributes.get("traceparent").stringValue()).isEqualTo(TRACEPARENT);
        }
    }
}
