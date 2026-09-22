package com.smit.flightops;

import com.smit.flightops.config.OutboxProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox's configuration refusing to start wrong.
 *
 * <p>Every value here has a failure mode that is invisible at startup and
 * expensive later. {@code batch-size: 0} makes the poller run every second and
 * publish nothing, which looks exactly like an empty queue. {@code
 * max-attempts: 0} means no row is ever claimed at all. A negative {@code
 * poll-interval} takes the scheduler down at a point where the stack trace
 * names Spring rather than this file. None of them fail a health check, so the
 * constructor fails instead, at startup, naming the property in the message
 * that a human will read in a pod log.
 *
 * <p>The tests are against the record directly and against the binder, because
 * they answer different questions. The record proves the bounds hold; the
 * binder proves the {@code @DefaultValue}s exist — a missing one binds to zero
 * or null rather than to the documented value, and no amount of constructor
 * testing sees that.
 */
class OutboxPropertiesTest {

    private static OutboxProperties bind(Map<String, String> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app.outbox", Bindable.of(OutboxProperties.class))
                .orElseThrow(() -> new IllegalStateException("nothing bound"));
    }

    private static Map<String, String> minimalConfiguration() {
        Map<String, String> properties = new HashMap<>();
        properties.put("app.outbox.enabled", "true");
        properties.put("app.outbox.poll-interval", "1000");
        properties.put("app.outbox.batch-size", "100");
        return properties;
    }

    /**
     * The one bound that is a relationship rather than a range, and the reason
     * this validation is written in Java instead of as {@code @Positive}.
     *
     * <p>Retention below an hour, with an hourly pruner, means a row can be
     * deleted minutes after it was published — so the operator who asks "did
     * booking 4471 publish?" finds nothing and cannot tell "published and
     * pruned" from "never recorded at all". Those two answers call for opposite
     * actions, which makes an empty result worse than no table.
     */
    @Test
    @DisplayName("a retention shorter than an hour is refused, with a reason")
    void tooShortARetentionIsRefused() {
        assertThatThrownBy(() -> new OutboxProperties(true, 1000, 100, 10,
                Duration.ofMinutes(30), Duration.ofHours(1), 1000,
                Duration.ofSeconds(2), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.outbox.retention")
                .hasMessageContaining("auditable");

        assertThatCode(() -> new OutboxProperties(true, 1000, 100, 10,
                Duration.ofHours(1), Duration.ofHours(1), 1000,
                Duration.ofSeconds(2), Duration.ofMinutes(5)))
                .as("exactly the floor is allowed; the message says 'at least'")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("every bound names the property it rejected")
    void eachBoundIsEnforcedAndNamed() {
        assertThatThrownBy(() -> new OutboxProperties(true, 0, 100, 10,
                Duration.ofDays(7), Duration.ofHours(1), 1000,
                Duration.ofSeconds(2), Duration.ofMinutes(5)))
                .hasMessageContaining("app.outbox.poll-interval");

        assertThatThrownBy(() -> new OutboxProperties(true, 1000, 0, 10,
                Duration.ofDays(7), Duration.ofHours(1), 1000,
                Duration.ofSeconds(2), Duration.ofMinutes(5)))
                .hasMessageContaining("app.outbox.batch-size");

        assertThatThrownBy(() -> new OutboxProperties(true, 1000, 100, 0,
                Duration.ofDays(7), Duration.ofHours(1), 1000,
                Duration.ofSeconds(2), Duration.ofMinutes(5)))
                .as("zero attempts means no row is ever claimed, and the poller looks idle")
                .hasMessageContaining("app.outbox.max-attempts");

        assertThatThrownBy(() -> new OutboxProperties(true, 1000, 100, 10,
                Duration.ofDays(7), Duration.ZERO, 1000,
                Duration.ofSeconds(2), Duration.ofMinutes(5)))
                .hasMessageContaining("app.outbox.prune-interval");

        assertThatThrownBy(() -> new OutboxProperties(true, 1000, 100, 10,
                Duration.ofDays(7), Duration.ofHours(1), 0,
                Duration.ofSeconds(2), Duration.ofMinutes(5)))
                .as("a zero batch size would make the pruner loop fifty times deleting nothing")
                .hasMessageContaining("app.outbox.prune-batch-size");

        assertThatThrownBy(() -> new OutboxProperties(true, 1000, 100, 10,
                Duration.ofDays(7), Duration.ofHours(1), 1000,
                Duration.ofSeconds(-1), Duration.ofMinutes(5)))
                .as("a negative wait would put the next attempt in the past")
                .hasMessageContaining("app.outbox.retry-backoff");

        assertThatThrownBy(() -> new OutboxProperties(true, 1000, 100, 10,
                Duration.ofDays(7), Duration.ofHours(1), 1000,
                Duration.ofMinutes(5), Duration.ofSeconds(2)))
                .as("a cap below the base shortens the first retry instead of bounding the last")
                .hasMessageContaining("app.outbox.max-retry-backoff");
    }

    /**
     * A deployment that sets none of the new keys must get the documented
     * behaviour rather than zeroes. This is the test that fails if somebody
     * drops a {@code @DefaultValue} while tidying the record — in which case
     * {@code maxAttempts} binds to 0 and the poller silently stops claiming
     * anything at all.
     */
    @Test
    @DisplayName("the defaults bind for a deployment that configures none of them")
    void theDefaultsAreTheDocumentedOnes() {
        OutboxProperties properties = bind(minimalConfiguration());

        assertThat(properties.maxAttempts()).isEqualTo(10);
        assertThat(properties.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.pruneInterval()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.pruneBatchSize()).isEqualTo(1000);
        assertThat(properties.retryBackoff()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.maxRetryBackoff()).isEqualTo(Duration.ofMinutes(5));
    }

    /**
     * The values in {@code application.yml} are written in the short form
     * ({@code 7d}, {@code 1h}), which is Boot's relaxed duration syntax and not
     * ISO-8601. Pinned because a record field typed {@code Duration} accepts
     * both and a reader cannot tell from the Java which one the yaml is using.
     */
    @Test
    @DisplayName("the short duration form used in application.yml binds")
    void theShortDurationFormBinds() {
        Map<String, String> properties = minimalConfiguration();
        properties.put("app.outbox.retention", "7d");
        properties.put("app.outbox.prune-interval", "90m");

        OutboxProperties bound = bind(properties);

        assertThat(bound.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(bound.pruneInterval()).isEqualTo(Duration.ofMinutes(90));
    }
}
