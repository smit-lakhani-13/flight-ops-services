package com.smit.flightops.config;

import org.springframework.boot.autoconfigure.condition.ConditionOutcome;
import org.springframework.boot.autoconfigure.condition.SpringBootCondition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * The switch for {@code OutboxPublisher} and {@code OutboxPruner}: whether
 * {@code app.outbox.enabled} is true, read the way {@link OutboxProperties} reads it.
 * {@code @ConditionalOnProperty} compared the raw string with {@code true}, while the
 * Binder also reads {@code on}, {@code yes} and {@code 1} as true. So
 * {@code OUTBOX_ENABLED=yes} bound {@code enabled=true} and created neither bean, and
 * outbox rows piled up unsent and unpruned with no error. Here the Binder decides
 * both, with the same default of true, and a value that is not a boolean stops
 * startup naming the property.
 *
 * <p>It binds a primitive {@code boolean}, as the record does, and not a
 * {@code Boolean}. An empty value converts to null. {@code OUTBOX_ENABLED=} gives one,
 * because a variable that is set, even to nothing, does not take the
 * {@code ${OUTBOX_ENABLED:true}} default. A {@code Boolean} would read null as missing
 * and match, where the record refuses it. The primitive refuses it here too, naming
 * the property, before any bean is created.
 */
public class OutboxEnabledCondition extends SpringBootCondition {

    private static final String PROPERTY = "app.outbox.enabled";

    @Override
    public ConditionOutcome getMatchOutcome(ConditionContext context, AnnotatedTypeMetadata metadata) {
        boolean enabled = Binder.get(context.getEnvironment()).bind(PROPERTY, boolean.class).orElse(true);
        return new ConditionOutcome(enabled, PROPERTY + " is " + enabled);
    }
}
