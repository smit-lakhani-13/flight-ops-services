package com.smit.flightops;

import com.smit.flightops.config.TimeConfig;
import com.smit.flightops.dto.CreateFlightRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @Future} on a departure time judges it against the {@link Clock} bean.
 * Hibernate Validator's own clock is {@code Clock.systemDefaultZone()}, so without
 * the customizer in {@link TimeConfig} a pinned clock would not move it. The context
 * holds Boot's validation auto-configuration, {@code TimeConfig}, and a fixed clock
 * marked primary so that it wins over {@code TimeConfig}'s own.
 */
class ValidationClockTest {

    private static final Clock AT_2100 = Clock.fixed(Instant.parse("2100-01-01T00:00:00Z"), ZoneOffset.UTC);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TimeConfig.class)
            .withBean("fixedClock", Clock.class, () -> AT_2100, definition -> definition.setPrimary(true));

    @Test
    @DisplayName("@Future reads the Clock bean, so a departure before the pinned time is refused")
    void futureReadsTheClockBean() {
        runner.run(context -> {
            Validator validator = context.getBean(Validator.class);

            assertThat(validator.validate(flightDeparting("2099-12-31T23:00:00Z")))
                    .extracting(ConstraintViolation::getPropertyPath)
                    .map(Object::toString)
                    .containsExactly("departureTime");
            assertThat(validator.validate(flightDeparting("2100-01-01T01:00:00Z"))).isEmpty();
        });
    }

    private static CreateFlightRequest flightDeparting(String departureTime) {
        return new CreateFlightRequest("UA123", "EWR", "LHR", 100, Instant.parse(departureTime));
    }
}
