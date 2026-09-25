package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 * The event transport mode, bound from {@code app.events.publisher}.
 *
 * <p>{@code @ConditionalOnProperty} on {@code LoggingEventPublisher} and
 * {@code SqsEventPublisher} picks the bean, and the same condition on
 * {@code AwsConfig} means a run in {@code log} mode builds no SQS client. A third
 * value matches neither condition, and the failure would be a missing
 * {@code EventPublisher} bean. This record rejects it first, naming the property
 * and the legal values; {@code OutboxPublisher} injects it for that reason.
 *
 * @param publisher {@code log} or {@code sqs}. The default {@code log} has to agree
 *                  with {@code matchIfMissing = true} on {@code LoggingEventPublisher}.
 */
@ConfigurationProperties(prefix = "app.events")
public record EventProperties(@DefaultValue("log") String publisher) {

    /** The complete set of modes, which is also what the failure message prints. */
    public static final Set<String> MODES = Set.of("log", "sqs");

    public EventProperties {
        if (!MODES.contains(publisher)) {
            throw new IllegalArgumentException(
                    "app.events.publisher must be one of " + MODES.stream().sorted().toList()
                            + ", not \"" + publisher + "\"");
        }
    }
}
