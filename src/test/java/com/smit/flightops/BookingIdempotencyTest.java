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
import java.util.List;
import java.util.concurrent.Callable;
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
 * The README's headline claim, proved end to end on H2 — real transactions, real
 * commits, no mocks and no Docker. Everything from the controller down is the
 * production wiring; only the database and the event transport differ.
 *
 * <p>Deliberately NOT {@code @Transactional}: a rolled-back test would let the
 * replay read uncommitted state from the same transaction and the test would
 * pass for the wrong reason. Because rows therefore survive each test, the
 * methods are ordered and use disjoint flight numbers.
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

    @Test
    @Order(1)
    @DisplayName("the seeder created the three demo flights the README curls against")
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
        BookingRequest request = new BookingRequest("UA123", "Smit Lakhani", 3, "demo-1");

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
    @DisplayName("a different key on the same flight is a genuinely new booking")
    void differentKeyBooksAgain() {
        int before = availableSeats("UA456");

        bookingService.book(new BookingRequest("UA456", "Passenger A", 2, "key-a"));
        bookingService.book(new BookingRequest("UA456", "Passenger B", 2, "key-b"));

        assertThat(availableSeats("UA456")).isEqualTo(before - 4);
        assertThat(bookingRepository.findByFlightNumber("UA456", Pageable.unpaged())).hasSize(2);
    }

    @Test
    @Order(5)
    @DisplayName("overselling rolls the whole transaction back — no seats debited, no booking row")
    void oversellLeavesNoTrace() {
        flightService.create(new CreateFlightRequest("UA001", "EWR", "LHR", 2,
                Instant.now().plus(Duration.ofHours(6))));

        assertThatThrownBy(() -> bookingService.book(
                new BookingRequest("UA001", "Smit Lakhani", 3, "oversell-1")))
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

        bookingService.book(new BookingRequest(" ua002 ", "Smit Lakhani", 1, "norm-1"));

        assertThat(availableSeats("UA002")).isEqualTo(9);
    }

    /**
     * REGRESSION for the idempotency-race fix. Ten threads hit {@code book()}
     * with the same idempotency key <em>and the same request</em> at the same
     * time — a genuine race, not a sequential replay, so {@code
     * findByIdempotencyKey} cannot short-circuit any of them. Before the fix,
     * the nine losers got 409 {@code DUPLICATE_REQUEST} from {@code
     * GlobalExceptionHandler}; this pins that every caller now gets the SAME
     * booking back instead, with seats debited exactly once, which is the
     * contract {@code BookingController.book}'s Javadoc actually claims.
     *
     * <p><b>"And the same request" is new, and it is the whole reason this test
     * changed.</b> It used to send {@code "Racer " + i} as the passenger name —
     * ten different bookings sharing one key — and assert that all ten callers
     * got the same booking. That assertion was the bug written down as the
     * contract: nine of those callers asked to book a seat for a different
     * person, were told 201, and got somebody else's reservation. The test
     * passed, and what it proved was that the service did the wrong thing
     * consistently. Adding the request fingerprint turned it red immediately,
     * which is the most useful thing a test can do on the day the behaviour it
     * pinned turns out to be wrong.
     *
     * <p>A retry is the same request arriving twice, so this test now sends the
     * same request twice — ten times, concurrently. The other half of the old
     * test's scenario is a real case too, and it is
     * {@link #racingCallersWithDifferentPayloadsOnOneKeyGetConflicts} below.
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
                            bookingService.book(new BookingRequest("ua003", "Smit Lakhani", 1, "race-1")))
                    .toList();

            List<Future<BookingDto>> futures = pool.invokeAll(attempts);
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
     * The other half of the race, and the one the fingerprint exists for: ten
     * callers share an idempotency key but ask for ten different bookings.
     *
     * <p>Exactly one of them should win and get 201. The other nine are
     * reusing a key for a different request, so each gets {@code
     * IdempotencyKeyConflictException} — 409 {@code IDEMPOTENCY_KEY_REUSED}
     * over HTTP. Crucially, only one seat is debited: the losers must leave no
     * trace, exactly as the oversell losers do.
     *
     * <p>This covers the fingerprint check inside {@code
     * BookingWriter.recoverReplay} rather than the one in {@code
     * BookingService.book}. The distinction matters and is easy to get wrong:
     * the check in {@code book} only sees committed rows, so under a true race
     * none of the ten sees any of the others there and all ten proceed to the
     * insert. Nine lose on the unique constraint and land in {@code
     * recoverReplay}. Had the fingerprint been compared only in {@code book},
     * this test would still return 201 nine times with the winner's booking —
     * the bug fixed for sequential callers and left in place for concurrent
     * ones, which is the version of the fix that looks complete and is not.
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

            List<Future<BookingDto>> futures = pool.invokeAll(attempts);

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

            assertThat(booked).as("exactly one caller books").isEqualTo(1);
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
     * The race the 50-seat fixtures above could never produce: the replay that
     * takes the LAST seat.
     *
     * <p>{@link #racingTenCallersOnTheSameKeyAllGetTheSameBooking} books one
     * seat out of fifty, so every loser reached the unique constraint with
     * seats still to spare and the recovery path worked. Give the flight
     * exactly one seat and the losers arrive after the winner has emptied it —
     * and until this was fixed, {@code insertNewBooking} debited seats before
     * it inserted, so they got {@code InsufficientSeatsException} and a 409
     * naming the wrong reason. The booking had been made. The client retrying
     * it was told the flight was full.
     *
     * <p>That is not a corner: the last seat is the one people retry over. The
     * fix is the second {@code findByIdempotencyKey} inside the flight row
     * lock, and this is the test that would have caught the bug. Capacity is
     * therefore 1 on purpose — a fixture with room to spare passes either way,
     * which is exactly how this survived two review passes.
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
                            bookingService.book(new BookingRequest("ua005", "Smit Lakhani", 1, "race-last-seat")))
                    .toList();

            List<BookingDto> results = pool.invokeAll(attempts).stream().map(f -> {
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
}
