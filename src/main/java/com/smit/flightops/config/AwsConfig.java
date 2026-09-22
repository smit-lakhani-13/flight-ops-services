package com.smit.flightops.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryMode;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.time.Duration;

/**
 * The SQS client, created only when the SQS publisher is selected, because building one
 * resolves a region and credentials eagerly and would stop a laptop with no AWS setup.
 * {@link DefaultCredentialsProvider} picks up a local profile in development and the
 * IRSA token on EKS, so there are no static keys.
 *
 * @see AwsProperties
 */
@Configuration
@ConditionalOnProperty(name = "app.events.publisher", havingValue = "sqs")
public class AwsConfig {

    /**
     * The SDK's default is no call timeout. {@code OutboxPublisher} sends while holding the
     * claimed rows and a pooled connection, so an endpoint that stops answering would pin
     * both until the socket gave up. {@code apiCallAttemptTimeout} bounds one attempt and
     * lets the retry policy try again; {@code apiCallTimeout} bounds the whole call,
     * retries included. The bound is per send: a drain that times out on every row takes
     * up to batch-size × 5s, while the other replicas skip the locked rows. A timed-out
     * send is retried on a later tick, not lost. {@code STANDARD} retries use a token
     * bucket, so a dependency failing for everyone is not retried harder.
     */
    @Bean(destroyMethod = "close")
    public SqsClient sqsClient(AwsProperties awsProperties) {
        return SqsClient.builder()
                .region(Region.of(awsProperties.region()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(5))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))
                        .retryStrategy(RetryMode.STANDARD)
                        .build())
                .build();
    }
}
