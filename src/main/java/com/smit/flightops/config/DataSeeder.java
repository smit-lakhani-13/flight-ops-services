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
 * Seeds the three demo flights the README curl walkthrough depends on.
 *
 * <p>Off in {@code prod} ({@code app.seed.enabled: false}) — a container that
 * writes rows on boot is a nasty surprise in a real environment.
 *
 * <p>Re-runnable against a persistent database: it skips any flight number that
 * already exists, so restarting the app does not duplicate rows or fail.
 * {@code saveAll} runs in its own transaction inside Spring Data, so no
 * {@code @Transactional} is needed on this class — which also sidesteps the
 * usual trap of annotating a runner method that the proxy may not intercept.
 *
 * <p>What this is NOT is concurrency-safe, and that is why it is disabled rather
 * than hardened for {@code prod}. The check-then-insert is two statements: two
 * pods booting together both see the flight missing, both insert, and
 * {@code uk_flights_flight_number} rejects the loser — which surfaces as a
 * {@code DataIntegrityViolationException} out of an {@link ApplicationRunner},
 * meaning the context fails to start and that pod crash-loops. Making it safe
 * would need an upsert ({@code ON CONFLICT DO NOTHING}) or a lock; the right
 * answer for a real environment is that reference data arrives by migration, not
 * by application startup.
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
