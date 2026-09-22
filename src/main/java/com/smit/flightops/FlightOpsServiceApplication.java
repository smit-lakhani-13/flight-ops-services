package com.smit.flightops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} runs {@code OutboxPublisher.drainOutbox} and
 * {@code OutboxPruner}. Without it both {@code @Scheduled} methods are inert, with no
 * error, while tests that call them directly still pass.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FlightOpsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlightOpsServiceApplication.class, args);
    }
}
