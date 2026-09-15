package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed, immutable config bound from the {@code app.aws.*} block.
 *
 * <p>A record beats {@code @Value} on fields here: the values are bound and
 * validated once at startup instead of being resolved per-injection-point, and
 * nothing can mutate them afterwards. Registered by
 * {@code @ConfigurationPropertiesScan} on the application class.
 */
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(String region, String sqsQueueUrl, String dynamodbTable) {
}
