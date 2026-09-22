package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, immutable config bound from the {@code app.aws.*} block.
 *
 * <p>A record beats {@code @Value} on fields here: the values are bound and
 * validated once at startup instead of being resolved per-injection-point, and
 * nothing can mutate them afterwards. Registered by
 * {@code @ConfigurationPropertiesScan} on the application class.
 *
 * <p>There is no {@code dynamodbTable} component, and its absence is
 * deliberate. DynamoDB belongs to the Lambda consumer, which is a separate
 * deployable with its own environment; this service never reads or writes that
 * table. A bound-but-unread property is worse than no property: it appears in
 * the ConfigMap, gets copied into every new environment, and the first person
 * to change it spends an afternoon working out why nothing happened.
 */
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(String region, String sqsQueueUrl) {
}
