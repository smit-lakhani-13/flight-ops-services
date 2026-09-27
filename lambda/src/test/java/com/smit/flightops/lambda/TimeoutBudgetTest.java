package com.smit.flightops.lambda;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The handler's DynamoDB call cap against the limits in {@code template.yaml},
 * which no other test reads. The handler puts a batch's messages one after
 * another, so a batch whose puts all time out takes {@code BatchSize} times the
 * cap. That must end inside the function's {@code Timeout}, or Lambda kills the
 * handler before it returns its failure list and the whole batch comes back,
 * the puts that worked included.
 */
class TimeoutBudgetTest {

    @Test
    @DisplayName("a batch whose puts all time out still ends inside the function's Timeout")
    void aBatchOfTimedOutPutsEndsInsideTheTimeout() throws IOException {
        String template = template();
        Duration timeout = Duration.ofSeconds(onlyValue(template, "Timeout"));
        int batchSize = onlyValue(template, "BatchSize");
        Duration callCap = BookingEventHandler.OVERRIDES.apiCallTimeout().orElseThrow();

        assertThat(callCap.multipliedBy(batchSize)).isLessThan(timeout);
    }

    /** The value on the template's one {@code key: number} line, at any indent. */
    private static int onlyValue(String template, String key) {
        List<String> values = Pattern.compile("(?m)^\\s+" + key + ":\\s*(\\d+)\\s*$")
                .matcher(template).results().map(match -> match.group(1)).toList();
        assertThat(values).as("%s lines in template.yaml", key).hasSize(1);
        return Integer.parseInt(values.getFirst());
    }

    /** From the module or from the repository root, as the build runs it. */
    private static String template() throws IOException {
        for (Path candidate : List.of(Path.of("template.yaml"), Path.of("lambda/template.yaml"))) {
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }
        throw new IllegalStateException("template.yaml not found from " + Path.of("").toAbsolutePath());
    }
}
