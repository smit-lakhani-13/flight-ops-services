package com.smit.flightops.config;

import com.smit.flightops.observability.OutboxMetrics;
import com.smit.flightops.repository.OutboxEventRepository;
import com.smit.flightops.service.EventPublisher;
import com.smit.flightops.service.OutboxPruner;
import com.smit.flightops.service.OutboxPublisher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * {@link OutboxEnabledCondition} and {@link OutboxProperties} reading
 * {@code app.outbox.enabled} the same way. The old {@code @ConditionalOnProperty}
 * compared the string with {@code true}, so {@code yes} or {@code on} bound
 * {@code enabled=true} and still created neither the drain nor the pruner.
 */
class OutboxEnabledConditionTest {

    /** Stands in for {@link OutboxPublisher} and {@link OutboxPruner}, which carry the same annotation. */
    @Configuration(proxyBeanMethods = false)
    @Conditional(OutboxEnabledCondition.class)
    static class Drain {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Drain.class);

    /**
     * The two real classes, so taking the condition off either, or putting the string
     * comparison back, fails {@link #theDrainAndThePrunerCarryTheCondition}. Their
     * collaborators are stubs: nothing schedules them here, so they are only constructed.
     */
    private final ApplicationContextRunner outbox = new ApplicationContextRunner()
            .withUserConfiguration(OutboxPublisher.class, OutboxPruner.class)
            .withBean(EventProperties.class, () -> new EventProperties("log"))
            .withBean(OutboxProperties.class, () -> bind(Map.of()))
            .withBean(OutboxEventRepository.class, () -> mock(OutboxEventRepository.class))
            .withBean(EventPublisher.class, () -> mock(EventPublisher.class))
            .withBean(OutboxMetrics.class, () -> new OutboxMetrics(new SimpleMeterRegistry(),
                    mock(OutboxEventRepository.class), bind(Map.of())))
            .withBean(TransactionTemplate.class,
                    () -> new TransactionTemplate(mock(PlatformTransactionManager.class)))
            .withBean(Clock.class, Clock::systemUTC);

    /** The record needs the two values that have no default. */
    private static OutboxProperties bind(Map<String, String> extra) {
        Map<String, String> properties = new HashMap<>(extra);
        properties.put("app.outbox.poll-interval", "1000");
        properties.put("app.outbox.batch-size", "100");
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app.outbox", Bindable.of(OutboxProperties.class))
                .orElseThrow(() -> new IllegalStateException("nothing bound"));
    }

    @ParameterizedTest(name = "app.outbox.enabled={0} runs the drain: {1}")
    @CsvSource({"true, true", "on, true", "yes, true", "1, true", "YES, true",
            "false, false", "off, false", "no, false", "0, false"})
    @DisplayName("the condition and the record agree on every boolean spelling the Binder reads")
    void theConditionAndTheRecordAgree(String value, boolean enabled) {
        runner.withPropertyValues("app.outbox.enabled=" + value)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(Drain.class)).hasSize(enabled ? 1 : 0);
                });
        assertThat(bind(Map.of("app.outbox.enabled", value)).enabled()).isEqualTo(enabled);
    }

    /** Both default to true; before, the record bound false while the beans were created. */
    @Test
    @DisplayName("with the property missing, the condition and the record both say enabled")
    void aMissingPropertyMeansEnabledForBoth() {
        runner.run(context -> assertThat(context).hasSingleBean(Drain.class));
        assertThat(bind(Map.of()).enabled()).isTrue();
    }

    /** A typo must not turn the drain off without a word. */
    @Test
    @DisplayName("a value that is not a boolean stops startup and names the property")
    void aValueThatIsNotABooleanStopsStartup() {
        runner.withPropertyValues("app.outbox.enabled=ture")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("app.outbox.enabled");
                });
    }

    /**
     * {@code OUTBOX_ENABLED=} is set, so it does not take the {@code true} default in
     * {@code application.yml}, and Spring converts the empty string to null. Bound to a
     * {@code Boolean}, the condition would have read that as missing and run the drain
     * while the record refused it.
     */
    @Test
    @DisplayName("an empty OUTBOX_ENABLED stops startup in the condition and in the record, naming the property")
    void anEmptyValueStopsStartupInBoth() {
        runner.withPropertyValues("OUTBOX_ENABLED=", "app.outbox.enabled=${OUTBOX_ENABLED:true}")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("Error processing condition")
                            .hasStackTraceContaining("app.outbox.enabled");
                });
        assertThatThrownBy(() -> bind(Map.of("app.outbox.enabled", "")))
                .isInstanceOf(BindException.class)
                .hasMessageContaining("app.outbox.enabled");
    }

    /** {@code yes} is the value the string comparison dropped; {@code false} needs any condition at all. */
    @ParameterizedTest(name = "app.outbox.enabled={0} creates OutboxPublisher and OutboxPruner: {1}")
    @CsvSource({"yes, true", "false, false"})
    @DisplayName("OutboxPublisher and OutboxPruner themselves carry the condition")
    void theDrainAndThePrunerCarryTheCondition(String value, boolean enabled) {
        outbox.withPropertyValues("app.outbox.enabled=" + value)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeansOfType(OutboxPublisher.class)).hasSize(enabled ? 1 : 0);
                    assertThat(context.getBeansOfType(OutboxPruner.class)).hasSize(enabled ? 1 : 0);
                });
    }
}
