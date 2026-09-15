package com.smit.flightops.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS clients, created only when the SQS publisher is actually selected.
 *
 * <p>Guarded by the same condition as {@link com.smit.flightops.service.SqsEventPublisher}:
 * building an {@code SqsClient} resolves a region and credentials eagerly, so an
 * unconditional bean would make a laptop with no AWS config fail to start.
 *
 * <p>{@link DefaultCredentialsProvider} is the whole point of IRSA on EKS — the
 * same code picks up a local profile in development and a projected service
 * account token in the cluster, with no static keys anywhere.
 */
@Configuration
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "sqs")
public class AwsConfig {

    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(AwsProperties awsProperties) {
        return SqsClient.builder()
                .region(Region.of(awsProperties.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
