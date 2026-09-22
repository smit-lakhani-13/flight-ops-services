package com.smit.flightops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@code @EnableScheduling} is here for exactly one task: {@code
 * OutboxPublisher.drainOutbox}. Without it the annotation on that method is
 * inert - no error, no warning, and the outbox simply fills up while every
 * test that calls it directly still passes.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class FlightOpsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlightOpsServiceApplication.class, args);
    }
}
