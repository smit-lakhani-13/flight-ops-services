package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The {@code app.aws.*} block, bound once at startup. There is no DynamoDB table name:
 * the table belongs to the Lambda consumer, and this service never reads or writes it.
 * A bound but unread property would still be copied into every ConfigMap, where
 * changing it would do nothing.
 *
 * @see AwsConfig
 */
@ConfigurationProperties(prefix = "app.aws")
public record AwsProperties(String region, String sqsQueueUrl) {
}
