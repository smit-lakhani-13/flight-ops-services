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

    /**
     * Timeouts are the important part of this bean, not the region.
     *
     * <p>The SDK's default {@code apiCallTimeout} is no timeout at all: a
     * client with no override waits as long as the socket does. That is
     * survivable for a batch job and not for this caller. {@code
     * SqsEventPublisher.publishBookingCreated} runs inside
     * {@code BookingWriter.insertNewBooking}'s transaction, holding a {@code
     * SELECT ... FOR UPDATE} on the flight row and one of ten Hikari
     * connections. An SQS endpoint that accepts the TCP connection and then
     * stops responding — a partition, a security group change, a VPC endpoint
     * going away — therefore blocks every other booking for that flight and
     * eats a connection from a pool of ten, for as long as the SDK is willing
     * to wait. With enough concurrent bookings that is the whole pool, and the
     * service stops serving reads it could perfectly well have served.
     * The default was not "wait a while", it was "hold a row lock until the
     * socket gives up".
     *
     * <p>Two timeouts, because they mean different things:
     * {@code apiCallAttemptTimeout} bounds one HTTP attempt and lets the
     * retry policy try again, which is what you want for a dropped packet;
     * {@code apiCallTimeout} bounds the whole call including every retry, and
     * is the one that actually protects the lock. Setting only the attempt
     * timeout is a common and subtle mistake — three retries of a 2-second
     * attempt is a 6-second call, and the number you thought you had set was 2.
     *
     * <p>{@code STANDARD} retry mode rather than the older {@code LEGACY}
     * default: it uses a retry-token bucket, so a dependency that is failing
     * for everyone stops being retried instead of having the retries pile on.
     *
     * <p>Neither timeout is the real fix for the lock — that is the outbox,
     * which takes the network call out of the transaction entirely. These are
     * the bound that makes the current design survivable until then.
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
