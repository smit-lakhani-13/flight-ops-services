package com.smit.flightops;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.exception.IdempotencyKeyConflictException;
import com.smit.flightops.exception.InsufficientSeatsException;
import com.smit.flightops.observability.BookingMetrics;
import com.smit.flightops.repository.BookingRepository;
import com.smit.flightops.repository.FlightRepository;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.BookingWriter;
import com.smit.flightops.service.EventPublisher;
import com.smit.flightops.service.FlightService;
import com.smit.flightops.service.LoggingEventPublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
    @Autowired private BookingWriter bookingWriter;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MeterRegistry meterRegistry;

    private int availableSeats(String flightNumber) {
        return flightRepository.findByFlightNumber(flightNumber)
                .map(Flight::getAvailableSeats)
                .orElseThrow();
    }

    private double cancellations(String outcome) {
        return meterRegistry.get(BookingMetrics.CANCELLATIONS).tag("outcome", outcome).counter().count();
    }

    /**
     * Whether, within 2s, some session is part way through an {@code INSERT} into
     * bookings. On H2 that is a caller waiting on another transaction's uncommitted
     * row in {@code uk_bookings_idempotency_key}; H2 shows that wait as RUNNING with
     * no {@code BLOCKER_ID}, so the statement is the signal. The 2s stays inside the
     * 3s lock timeout.
     */
    private boolean aBookingInsertIsWaiting() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() - deadline < 0) {
            if (jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM INFORMATION_SCHEMA.SESSIONS
                    WHERE LOWER(EXECUTING_STATEMENT) LIKE 'insert into bookings%'
                    """, Integer.class) > 0) {
                return true;
            }
            Thread.onSpinWait();
        }
        return false;
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
    @DisplayName("REGRESSION: 10 concurrent callers, same key, same request -> "
                 + "one booking, ALL ten get it back, zero errors")
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
    @DisplayName("REGRESSION: 10 concurrent callers racing for the LAST seat on one key -> "
                 + "one booking, all ten get it, no 409")
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

    /**
     * Ten retries of one {@code DELETE} at once. The flight row lock serialises them and
     * each reads the booking under its own row lock, so only the first sees it active.
     * The 1-seat booking stays sold: on a flight that is otherwise empty, the clamp in
     * {@code Flight.releaseSeats} would turn a double credit back into a full flight.
     */
    @Test
    @Order(11)
    @DisplayName("10 concurrent cancels of one booking -> "
                 + "seats credited once, one cancelled and nine already_cancelled")
    void tenConcurrentCancelsReleaseTheSeatsOnce() throws Exception {
        flightService.create(new CreateFlightRequest("ua012", "ewr", "atl", 10,
                Instant.now().plus(Duration.ofHours(6))));
        bookingService.book(new BookingRequest("ua012", "Jane Doe", 1, "cancel-race-keep"));
        BookingDto target = bookingService.book(new BookingRequest("ua012", "Test Passenger", 3, "cancel-race-1"));
        assertThat(availableSeats("UA012")).isEqualTo(6);

        double cancelledBefore = cancellations(BookingMetrics.CANCELLED);
        double noOpsBefore = cancellations(BookingMetrics.ALREADY_CANCELLED);

        int callers = 10;
        try (ExecutorService pool = Executors.newFixedThreadPool(callers)) {
            List<Callable<BookingDto>> cancels = IntStream.range(0, callers)
                    .<Callable<BookingDto>>mapToObj(i -> () -> bookingService.cancel(target.bookingId()))
                    .toList();

            Set<Instant> cancelledAt = startTogether(pool, cancels).stream().map(f -> {
                try {
                    return f.get().cancelledAt();
                } catch (Exception e) {
                    throw new AssertionError("a retried cancellation should not fail", e);
                }
            }).collect(Collectors.toSet());

            assertThat(cancelledAt)
                    .as("every caller sees the one cancellation that happened")
                    .hasSize(1)
                    .doesNotContainNull();
            assertThat(bookingRepository.findById(target.bookingId()).orElseThrow().getCancelledAt())
                    .isEqualTo(cancelledAt.iterator().next());
            assertThat(availableSeats("UA012"))
                    .as("3 seats credited once; the kept booking still holds its seat")
                    .isEqualTo(9);
            assertThat(cancellations(BookingMetrics.CANCELLED))
                    .as("one call released the seats")
                    .isEqualTo(cancelledBefore + 1);
            assertThat(cancellations(BookingMetrics.ALREADY_CANCELLED))
                    .as("the other nine were no-ops")
                    .isEqualTo(noOpsBefore + 9);
        }
    }

    /**
     * The constraint path of {@code oneKeyRacedAcrossTwoFlightsBooksOnce}, on every run.
     * The holder inserts the key on UA013 and keeps its transaction open, so the loser
     * on UA014 passes the pre-check and the in-lock re-read and waits in its insert on
     * {@code uk_bookings_idempotency_key}. When the holder commits, that insert fails
     * and {@code recoverReplay} must answer with the key-reuse conflict.
     */
    @Test
    @Order(12)
    @DisplayName("one key on two flights, the loser waiting in its insert on the held key -> "
                 + "conflict once the holder commits")
    void aCrossFlightLoserBlockedOnTheKeyGetsTheConflict() throws Exception {
        flightService.create(new CreateFlightRequest("ua013", "ewr", "den", 10,
                Instant.now().plus(Duration.ofHours(6))));
        flightService.create(new CreateFlightRequest("ua014", "ewr", "phx", 10,
                Instant.now().plus(Duration.ofHours(6))));

        CountDownLatch inserted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        // As in LockTimeoutTest, close() waits for the holder, which waits for
        // release, so the countDown in finally must stay inside the resources.
        try (ExecutorService holderThread = Executors.newSingleThreadExecutor();
             ExecutorService loserThread = Executors.newSingleThreadExecutor()) {
            try {
                Future<BookingDto> holder = holderThread.submit(() ->
                        new TransactionTemplate(transactionManager).execute(status -> {
                            BookingDto held = bookingWriter.insertNewBooking(
                                    new BookingRequest("ua013", "Test Passenger", 1, "cross-constraint-1"));
                            inserted.countDown();
                            try {
                                release.await(30, TimeUnit.SECONDS);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return held;
                        }));
                assertThat(inserted.await(10, TimeUnit.SECONDS))
                        .as("the holder should have inserted the key without committing")
                        .isTrue();

                Future<BookingDto> loser = loserThread.submit(() -> bookingService.book(
                        new BookingRequest("ua014", "Jane Doe", 1, "cross-constraint-1")));

                // Reaching the insert means the pre-check and the in-lock re-read
                // both missed the holder's uncommitted row.
                assertThat(aBookingInsertIsWaiting())
                        .as("the loser should be waiting in its insert on the held key")
                        .isTrue();
                assertThat(loser).isNotDone();

                release.countDown();
                BookingDto winner = holder.get(10, TimeUnit.SECONDS);

                assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause()
                        .isInstanceOf(IdempotencyKeyConflictException.class);
                assertThat(bookingRepository.findByIdempotencyKey("cross-constraint-1"))
                        .get()
                        .satisfies(b -> {
                            assertThat(b.getId()).isEqualTo(winner.bookingId());
                            assertThat(b.getFlight().getFlightNumber()).isEqualTo("UA013");
                        });
                assertThat(bookingRepository.findByFlightNumber("UA013", Pageable.unpaged())).hasSize(1);
                assertThat(availableSeats("UA013")).isEqualTo(9);
                assertThat(availableSeats("UA014"))
                        .as("the loser's debit rolled back with its insert")
                        .isEqualTo(10);
                assertThat(bookingRepository.findByFlightNumber("UA014", Pageable.unpaged())).isEmpty();
            } finally {
                release.countDown();
            }
        }
    }
}
