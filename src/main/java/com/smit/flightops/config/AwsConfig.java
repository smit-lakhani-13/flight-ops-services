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
     * survivable for a batch job and not for this caller.
     *
     * <p>This send now happens in {@code OutboxPublisher.drainOutbox}, not on
     * the request path — the outbox took the network call out of the booking
     * transaction, so an unresponsive queue no longer holds a {@code SELECT
     * ... FOR UPDATE} on a flight row while a passenger waits. The timeouts
     * still matter, and it is worth being exact about what they protect now
     * rather than leaving the old reason in place: the drain holds locks on the
     * outbox rows it claimed and one of ten Hikari connections for the duration
     * of the batch. An SQS endpoint that accepts the TCP connection and then
     * stops answering — a partition, a security-group change, a VPC endpoint
     * going away — would otherwise pin that connection until the socket gave
     * up, once per replica, while {@code fixedDelay} politely waits for a drain
     * that is never going to finish. Events would stop flowing and nothing
     * would say why.
     *
     * <p>Two timeouts, because they mean different things:
     * {@code apiCallAttemptTimeout} bounds one HTTP attempt and lets the
     * retry policy try again, which is what you want for a dropped packet;
     * {@code apiCallTimeout} bounds the whole call including every retry.
     * Setting only the attempt timeout is a common and subtle mistake — three
     * retries of a 2-second attempt is a 6-second call, and the number you
     * thought you had set was 2.
     *
     * <p><b>It bounds one send, not the drain.</b> That distinction is worth
     * stating precisely, because this comment used to get it wrong.
     * {@code OutboxPublisher} sends the claimed rows one at a time, so a tick
     * is bounded at {@code app.outbox.batch-size} × this value — 100 × 5s,
     * eight and a bit minutes, if every send times out. That is the intended
     * trade rather than an oversight: the rows are locked {@code FOR UPDATE
     * SKIP LOCKED}, so the other replicas step straight past them and keep
     * draining, and every one of those sends is an attempt that has to be made
     * before the queue can be declared unreachable. Shrinking the batch size
     * shortens the worst case directly, at the cost of more claims per second.
     *
     * <p>A send that times out is not a lost event. {@code OutboxPublisher}
     * catches it, increments {@code attempts}, records the message on the row
     * and leaves {@code published_at} null, so the next tick tries again. The
     * timeout turns an unbounded stall into a retry, which is the entire
     * reason to have one.
     *
     * <p>{@code STANDARD} retry mode rather than the older {@code LEGACY}
     * default: it uses a retry-token bucket, so a dependency that is failing
     * for everyone stops being retried instead of having the retries pile on.
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
