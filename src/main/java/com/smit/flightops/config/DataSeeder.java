package com.smit.flightops.config;

import com.smit.flightops.entity.Flight;
import com.smit.flightops.repository.FlightRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Seeds the three demo flights the README walkthrough uses, skipping any that exist, so
 * a restart against a persistent database neither duplicates nor fails. It is off in
 * {@code prod} because the check-then-insert is not safe for two pods booting at once:
 * the loser hits {@code uk_flights_flight_number} and fails startup. Reference data in a
 * real environment belongs in a migration.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final FlightRepository flightRepository;
    private final Clock clock;

    public DataSeeder(FlightRepository flightRepository, Clock clock) {
        this.flightRepository = flightRepository;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Instant base = clock.instant().truncatedTo(ChronoUnit.MICROS);

        List<Flight> wanted = List.of(
                new Flight("UA123", "EWR", "LHR", 180, base.plus(Duration.ofHours(8))),
                new Flight("UA456", "ORD", "SFO", 150, base.plus(Duration.ofHours(12))),
                new Flight("UA789", "EWR", "SFO", 200, base.plus(Duration.ofHours(26))));

        List<Flight> missing = wanted.stream()
                .filter(f -> !flightRepository.existsByFlightNumber(f.getFlightNumber()))
                .toList();

        if (missing.isEmpty()) {
            log.info("Seed skipped: all {} demo flights already present", wanted.size());
            return;
        }

        flightRepository.saveAll(missing);
        log.info("Seeded {} flight(s): {}", missing.size(),
                 missing.stream().map(Flight::getFlightNumber).toList());
    }
}
