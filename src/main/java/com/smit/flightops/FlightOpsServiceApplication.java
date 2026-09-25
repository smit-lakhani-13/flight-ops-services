package com.smit.flightops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} runs {@code OutboxPublisher.drainOutbox} and
 * {@code OutboxPruner}. Without it both {@code @Scheduled} methods are inert, with no
 * error, while tests that call them directly still pass.
 *
 * <p>The application defines no {@code TaskScheduler}, and Boot's default runs
 * fixed-delay jobs on one thread, with virtual threads on or off. So both jobs run
 * one at a time: a prune run delays the next drain, and a slow drain delays the
 * prune. The comment on {@code app.outbox.prune-interval} in
 * {@code application.yml} says why that is acceptable.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FlightOpsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlightOpsServiceApplication.class, args);
    }
}
