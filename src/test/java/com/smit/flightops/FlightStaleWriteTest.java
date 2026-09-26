package com.smit.flightops;

import com.smit.flightops.dto.BookingDto;
import com.smit.flightops.dto.BookingRequest;
import com.smit.flightops.dto.CreateFlightRequest;
import com.smit.flightops.entity.Flight;
import com.smit.flightops.entity.FlightStatus;
import com.smit.flightops.repository.FlightRepository;
import com.smit.flightops.service.BookingService;
import com.smit.flightops.service.FlightService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Flight's {@code @Version} against a stale write. Flight has no
 * {@code @DynamicUpdate}, so a status change writes every column back,
 * {@code available_seats} included. A status change or cancellation that loaded the
 * flight before a booking committed must fail when it writes, not put the sold
 * seats back on sale. {@code FlightControllerTest} maps that failure to 409 from a
 * mocked service; this class lets a real booking commit in between.
 *
 * <p>The held transaction reads the flight first, without a lock, as
 * {@code FlightService} does, so the booking's {@code SELECT ... FOR UPDATE} can
 * commit before the write. The service then finds that same stale copy in the
 * transaction and writes it back. Whether the write reaches the database at a
 * flush inside the service or at the commit, it must fail. Not
 * {@code @Transactional}, for the reason {@code BookingIdempotencyTest} gives, and
 * on its own database, so the held transaction cannot touch another test's flights.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:h2:mem:flightstalewrite;DB_CLOSE_DELAY=-1")
class FlightStaleWriteTest {

    @Autowired private FlightService flightService;
    @Autowired private BookingService bookingService;
    @Autowired private FlightRepository flightRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private void createFlight(String flightNumber) {
        flightService.create(new CreateFlightRequest(flightNumber, "EWR", "SEA", 10,
                Instant.now().plus(Duration.ofHours(6))));
    }

    private Flight flight(String flightNumber) {
        return flightRepository.findByFlightNumber(flightNumber).orElseThrow();
    }

    /**
     * Reads the flight in a transaction, books 3 seats on another thread and waits
     * for that booking to commit, then runs {@code staleWrite} in the same
     * transaction. The wait is bounded: if the read ever locks the row, the booking
     * cannot commit here and the callback fails instead of hanging.
     */
    private void staleWriteAfterABooking(String flightNumber, String key, Consumer<String> staleWrite) {
        try (ExecutorService booker = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                assertThat(flight(flightNumber).getAvailableSeats()).as("the stale read").isEqualTo(10);

                Future<BookingDto> booking = booker.submit(() -> bookingService.book(
                        new BookingRequest(flightNumber, "Test Passenger", 3, key)));
                try {
                    booking.get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new AssertionError("the booking should commit after the stale read", e);
                }

                staleWrite.accept(flightNumber);
            });
        }
    }

    @Test
    @DisplayName("a status change that read the flight before a booking committed fails; the sold seats stay sold")
    void aStaleStatusChangeLosesToABooking() {
        createFlight("UA010");

        assertThatThrownBy(() -> staleWriteAfterABooking("UA010", "stale-1",
                fn -> flightService.updateStatus(fn, FlightStatus.DELAYED)))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(flight("UA010")).satisfies(f -> {
            assertThat(f.getAvailableSeats()).as("the 3 sold seats stay sold").isEqualTo(7);
            assertThat(f.getStatus()).as("the stale change rolled back").isEqualTo(FlightStatus.SCHEDULED);
        });
    }

    @Test
    @DisplayName("a cancellation that read the flight before a booking committed fails; the sold seats stay sold")
    void aStaleCancellationLosesToABooking() {
        createFlight("UA011");

        assertThatThrownBy(() -> staleWriteAfterABooking("UA011", "stale-2", flightService::cancel))
                .isInstanceOf(OptimisticLockingFailureException.class);

        assertThat(flight("UA011")).satisfies(f -> {
            assertThat(f.getAvailableSeats()).as("the 3 sold seats stay sold").isEqualTo(7);
            assertThat(f.getStatus()).as("the stale cancellation rolled back").isEqualTo(FlightStatus.SCHEDULED);
        });
    }
}
