package com.smit.flightops;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.IdempotencyKeyConflictException;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.EventPublisher;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.service.LoggingEventPublisher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The README's idempotency claim, proved on H2 with real transactions and commits,
 * no mocks and no Docker. Everything from {@code BookingService} down is the
 * production wiring; only the database and the event transport differ.
 *
 * <p>Not {@code @Transactional}: a test transaction would let the replay read
 * uncommitted state and pass for the wrong reason. Rows therefore survive each
 * test, so the methods are ordered and use disjoint flight numbers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BookingIdempotencyTest {

    @Autowired private BookingService bookingService;
    @Autowired private FlightService flightService;
    @Autowired private FlightRepository flightRepository;
    @Autowired private BookingRepository bookingRepository;
    @Autowired private EventPublisher eventPublisher;

    private int availableSeats(String flightNumber) {
        return flightRepository.findByFlightNumber(flightNumber)
                .map(Flight::getAvailableSeats)
                .orElseThrow();
    }

    /**
     * Submits every caller behind one start gate, then opens it. That makes the calls
     * overlap; it does not decide which path each loser takes.
     */
    private static <T> List<Future<T>> startTogether(ExecutorService pool, List<Callable<T>> callers) {
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (Callable<T> caller : callers) {
            futures.add(pool.submit(() -> {
                go.await();
                return caller.call();
            }));
        }
        go.countDown();
        return futures;
    }

    @Test
    @Order(1)
    @DisplayName("the seeder created the three demo flights local runs use")
    void seederRan() {
        assertThat(flightRepository.findByFlightNumber("UA123"))
                .get()
                .satisfies(f -> {
                    assertThat(f.getTotalSeats()).isEqualTo(180);
                    assertThat(f.getAvailableSeats()).isEqualTo(180);
                    assertThat(f.getOrigin()).isEqualTo("EWR");
                    assertThat(f.getDestination()).isEqualTo("LHR");
                });
        assertThat(flightRepository.existsByFlightNumber("UA456")).isTrue();
        assertThat(flightRepository.existsByFlightNumber("UA789")).isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("with no AWS configured the log publisher is wired, not the SQS one")
    void logPublisherIsActiveByDefault() {
        assertThat(eventPublisher).isInstanceOf(LoggingEventPublisher.class);
    }

    @Test
    @Order(3)
    @DisplayName("booking 3 seats then replaying the same key 5 times leaves 177 seats and ONE booking")
    void replayingTheSameKeyBooksOnce() {
        BookingRequest request = new BookingRequest("UA123", "Jane Doe", 3, "demo-1");

        BookingDto first = bookingService.book(request);
        assertThat(availableSeats("UA123")).isEqualTo(177);

        for (int attempt = 0; attempt < 5; attempt++) {
            BookingDto replay = bookingService.book(request);
            assertThat(replay.bookingId()).isEqualTo(first.bookingId());
            assertThat(replay.createdAt()).isEqualTo(first.createdAt());
        }

        assertThat(availableSeats("UA123")).as("no seats lost to retries").isEqualTo(177);
        assertThat(bookingRepository.findByFlightNumber("UA123", Pageable.unpaged())).hasSize(1);
    }

    @Test
    @Order(4)
    @DisplayName("a different key on the same flight is a new booking")
    void differentKeyBooksAgain() {
        int before = availableSeats("UA456");

        bookingService.book(new BookingRequest("UA456", "Passenger A", 2, "key-a"));
        bookingService.book(new BookingRequest("UA456", "Passenger B", 2, "key-b"));

        assertThat(availableSeats("UA456")).isEqualTo(before - 4);
        assertThat(bookingRepository.findByFlightNumber("UA456", Pageable.unpaged())).hasSize(2);
    }

    @Test
    @Order(5)
    @DisplayName("overselling is refused before anything is written: no seats debited, no booking row")
    void oversellLeavesNoTrace() {
        flightService.create(new CreateFlightRequest("UA001", "EWR", "LHR", 2,
                Instant.now().plus(Duration.ofHours(6))));

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest("UA001", "Jane Doe", 3, "oversell-1")))
                .isInstanceOf(InsufficientSeatsException.class)
                .hasMessageContaining("UA001");

        assertThat(availableSeats("UA001")).isEqualTo(2);
        assertThat(bookingRepository.findByIdempotencyKey("oversell-1")).isEmpty();
        assertThat(bookingRepository.findByFlightNumber("UA001", Pageable.unpaged())).isEmpty();
    }

    @Test
    @Order(6)
    @DisplayName("lower-case input still finds the flight, and the booking still reserves seats")
    void flightNumberIsNormalised() {
        flightService.create(new CreateFlightRequest("ua002", "ewr", "sfo", 10,
                Instant.now().plus(Duration.ofHours(6))));

        bookingService.book(new BookingRequest(" ua002 ", "Jane Doe", 1, "norm-1"));

        assertThat(availableSeats("UA002")).isEqualTo(9);
    }

    /**
     * Ten threads call {@code book()} with one key and the same request at once. On one
     * flight the {@code FOR UPDATE} lock serialises them, so each loser is answered by
     * the pre-check replay or by the in-lock re-read ({@code LostIdempotencyRaceException},
     * then {@code recoverReplay}). Every path must return the winner's booking and
     * debit one seat. Different requests on one key are the next test.
     */
    @Test
    @Order(7)
    @DisplayName("REGRESSION: 10 concurrent callers, same key, same request -> one booking, ALL ten get it back, zero errors")
    void racingTenCallersOnTheSameKeyAllGetTheSameBooking() throws Exception {
        flightService.create(new CreateFlightRequest("ua003", "ewr", "ord", 50,
                Instant.now().plus(Duration.ofHours(6))));

        int callers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<BookingDto>> attempts = IntStream.range(0, callers)
                    .<Callable<BookingDto>>mapToObj(i -> () ->
                            bookingService.book(new BookingRequest("ua003", "Jane Doe", 1, "race-1")))
                    .toList();

            List<Future<BookingDto>> futures = startTogether(pool, attempts);
            List<BookingDto> results = futures.stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new AssertionError("no caller should see an exception", e);
                }
            }).collect(Collectors.toList());

            assertThat(results).hasSize(callers);
            assertThat(results.stream().map(BookingDto::bookingId).distinct())
                    .as("every caller gets back the SAME booking id")
                    .hasSize(1);
            assertThat(availableSeats("UA003"))
                    .as("one seat debited, not ten")
                    .isEqualTo(49);
            assertThat(bookingRepository.findByFlightNumber("UA003", Pageable.unpaged())).hasSize(1);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * Ten callers share a key but ask for ten different bookings. One wins; the other
     * nine get {@code IdempotencyKeyConflictException} (409 {@code IDEMPOTENCY_KEY_REUSED}
     * over HTTP) and debit nothing. A loser meets the fingerprint check in the pre-check
     * or, after the in-lock re-read, in {@code recoverReplay}; which one varies by run.
     */
    @Test
    @Order(8)
    @DisplayName("10 concurrent callers, same key, DIFFERENT requests -> one 201 and nine conflicts, one seat debited")
    void racingCallersWithDifferentPayloadsOnOneKeyGetConflicts() throws Exception {
        flightService.create(new CreateFlightRequest("ua004", "ewr", "sea", 50,
                Instant.now().plus(Duration.ofHours(6))));

        int callers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<BookingDto>> attempts = IntStream.range(0, callers)
                    .<Callable<BookingDto>>mapToObj(i -> () ->
                            bookingService.book(new BookingRequest("ua004", "Racer " + i, 1, "race-2")))
                    .toList();

            List<Future<BookingDto>> futures = startTogether(pool, attempts);

            int booked = 0;
            int conflicts = 0;
            for (Future<BookingDto> future : futures) {
                try {
                    future.get();
                    booked++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause())
                            .as("the only acceptable failure here is a key-reuse conflict")
                            .isInstanceOf(IdempotencyKeyConflictException.class);
                    conflicts++;
                }
            }

            assertThat(booked).as("one caller books, no more").isEqualTo(1);
            assertThat(conflicts).as("the other nine are told the key is taken").isEqualTo(callers - 1);
            assertThat(availableSeats("UA004"))
                    .as("one seat debited, not ten - the losers leave no trace")
                    .isEqualTo(49);
            assertThat(bookingRepository.findByFlightNumber("UA004", Pageable.unpaged())).hasSize(1);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * The replay that races for the last seat. With one seat, a loser arrives after
     * the winner has emptied the flight. The in-lock re-read runs before
     * {@code reserveSeats}, so the loser gets the winner's booking, not
     * {@code InsufficientSeatsException}. A fixture with seats to spare passes without
     * that ordering, which is why capacity is 1.
     */
    @Test
    @Order(9)
    @DisplayName("REGRESSION: 10 concurrent callers racing for the LAST seat on one key -> one booking, all ten get it, no 409")
    void racingCallersOnTheLastSeatAllGetTheSameBooking() throws Exception {
        flightService.create(new CreateFlightRequest("ua005", "ewr", "sfo", 1,
                Instant.now().plus(Duration.ofHours(6))));

        int callers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<BookingDto>> attempts = IntStream.range(0, callers)
                    .<Callable<BookingDto>>mapToObj(i -> () ->
                            bookingService.book(new BookingRequest("ua005", "Jane Doe", 1, "race-last-seat")))
                    .toList();

            List<BookingDto> results = startTogether(pool, attempts).stream().map(f -> {
                try {
                    return f.get();
                } catch (Exception e) {
                    throw new AssertionError(
                            "a replay of a booking that succeeded must not fail, least of all "
                            + "with INSUFFICIENT_SEATS", e);
                }
            }).collect(Collectors.toList());

            assertThat(results.stream().map(BookingDto::bookingId).distinct())
                    .as("every caller gets back the SAME booking id")
                    .hasSize(1);
            assertThat(availableSeats("UA005"))
                    .as("the one seat is debited once")
                    .isZero();
            assertThat(bookingRepository.findByFlightNumber("UA005", Pageable.unpaged())).hasSize(1);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    /**
     * One key on two flights. The callers lock different flight rows, so a loser can
     * reach {@code uk_bookings_idempotency_key} and then {@code recoverReplay}'s
     * fingerprint check. It reaches the constraint on most runs, not all (4 of 6
     * measured on H2); on the others the pre-check or the in-lock re-read settles it.
     * Either way one booking is made, the winner's flight gets replays and the other
     * flight gets conflicts.
     */
    @Test
    @Order(10)
    @DisplayName("10 concurrent callers, one key, two flights -> one booking, one seat debited in total")
    void oneKeyRacedAcrossTwoFlightsBooksOnce() throws Exception {
        flightService.create(new CreateFlightRequest("ua006", "ewr", "bos", 50,
                Instant.now().plus(Duration.ofHours(6))));
        flightService.create(new CreateFlightRequest("ua007", "ewr", "mia", 50,
                Instant.now().plus(Duration.ofHours(6))));

        int callers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            List<Callable<BookingDto>> attempts = IntStream.range(0, callers)
                    .<Callable<BookingDto>>mapToObj(i -> () -> bookingService.book(new BookingRequest(
                            i % 2 == 0 ? "ua006" : "ua007", "Jane Doe", 1, "cross-flight")))
                    .toList();

            List<Long> bookedIds = new ArrayList<>();
            int conflicts = 0;
            for (Future<BookingDto> future : startTogether(pool, attempts)) {
                try {
                    bookedIds.add(future.get().bookingId());
                } catch (ExecutionException e) {
                    assertThat(e.getCause())
                            .as("the only acceptable failure here is a key-reuse conflict")
                            .isInstanceOf(IdempotencyKeyConflictException.class);
                    conflicts++;
                }
            }

            assertThat(bookingRepository.findByIdempotencyKey("cross-flight")).isPresent();
            assertThat(bookingRepository.findByFlightNumber("UA006", Pageable.unpaged()).getTotalElements()
                    + bookingRepository.findByFlightNumber("UA007", Pageable.unpaged()).getTotalElements())
                    .as("one row for the key across both flights")
                    .isEqualTo(1);
            assertThat(availableSeats("UA006") + availableSeats("UA007"))
                    .as("one seat debited in total")
                    .isEqualTo(99);
            assertThat(bookedIds).as("the winner's flight: five callers, one booking").hasSize(5);
            assertThat(bookedIds.stream().distinct()).hasSize(1);
            assertThat(conflicts).as("the other flight: five conflicts").isEqualTo(5);
        } finally {
            pool.shutdown();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }
}
