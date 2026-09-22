package com.smit.flightops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Set;

/**
 * The event transport mode, bound from {@code app.events.publisher}.
 *
 * <p><b>Nothing injects this record, and that is deliberate.</b> The mode is
 * consumed by {@code @ConditionalOnProperty} on {@code LoggingEventPublisher}
 * and {@code SqsEventPublisher}, which is the right mechanism — it decides
 * whether a bean is <em>defined</em>, so an SQS client is never constructed on
 * a machine with no credentials. What that mechanism cannot do is reject a
 * value it does not recognise: a third value simply matches neither condition,
 * no {@code EventPublisher} bean is defined, and the application fails while
 * constructing {@code OutboxPublisher} with a message about an unsatisfied
 * dependency on an interface. The property that actually caused it is not in
 * that message, and the conditions report that would explain it only appears
 * with debug logging on.
 *
 * <p>That failure shipped. {@code compose.yaml} set {@code
 * APP_EVENTS_PUBLISHER=noop} — a value that had never existed — and the
 * documented {@code docker compose up} stack could not start. The typo was in
 * two documents as well, which is what a value nothing validates looks like
 * after a few weeks.
 *
 * <p>So this record exists to be bound, not to be read. Binding happens at
 * startup whether or not anything asks for the bean, and an unrecognised value
 * fails there instead, naming the property and listing what is legal.
 *
 * @param publisher {@code log} or {@code sqs}. The default is {@code log},
 *                  matching {@code matchIfMissing = true} on
 *                  {@code LoggingEventPublisher} — the two defaults have to
 *                  agree, or this class would reject a configuration that
 *                  actually works.
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
