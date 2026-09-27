package com.smit.flightops.entity;

import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.InsufficientSeatsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Unit tests on the aggregate, with no Spring and no database: the seat invariant is a domain rule. */
class FlightTest {

    private Flight flight() {
        return new Flight("UA123", "EWR", "LHR", 180, Instant.now().plus(Duration.ofHours(8)));
    }

    @Test
    @DisplayName("a new flight starts with every seat available and SCHEDULED")
    void newFlightStartsFull() {
        Flight flight = flight();

        assertThat(flight.getTotalSeats()).isEqualTo(180);
        assertThat(flight.getAvailableSeats()).isEqualTo(180);
        assertThat(flight.getStatus()).isEqualTo(FlightStatus.SCHEDULED);
    }

    @Test
    @DisplayName("the departure time keeps the microseconds the column stores")
    void departureTimeIsTruncatedToMicroseconds() {
        Flight flight = new Flight("UA123", "EWR", "LHR", 180, Instant.parse("2099-01-01T10:00:00.123456789Z"));

        assertThat(flight.getDepartureTime()).isEqualTo(Instant.parse("2099-01-01T10:00:00.123456Z"));
    }

    @Test
    void reserveSeatsReducesAvailability() {
        Flight flight = flight();

        flight.reserveSeats(3);

        assertThat(flight.getAvailableSeats()).isEqualTo(177);
        assertThat(flight.getTotalSeats()).isEqualTo(180);
    }

    @Test
    @DisplayName("reserving more than remains fails and leaves the count untouched")
    void reserveMoreThanAvailableFails() {
        Flight flight = flight();
        flight.reserveSeats(179);

        assertThatThrownBy(() -> flight.reserveSeats(2))
                .isInstanceOf(InsufficientSeatsException.class)
                .hasMessageContaining("UA123")
                .hasMessageContaining("1 seat(s) available");

        assertThat(flight.getAvailableSeats()).isEqualTo(1);
    }

    @Test
    void reserveZeroOrNegativeIsRejected() {
        Flight flight = flight();

        assertThatThrownBy(() -> flight.reserveSeats(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> flight.reserveSeats(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(flight.getAvailableSeats()).isEqualTo(180);
    }

    @Test
    @DisplayName("releaseSeats never lets availability exceed capacity")
    void releaseSeatsClampsAtCapacity() {
        Flight flight = flight();
        flight.reserveSeats(2);

        flight.releaseSeats(50);

        assertThat(flight.getAvailableSeats()).isEqualTo(180);
    }

    @Test
    void cancelIsASoftStatusTransition() {
        Flight flight = flight();

        flight.cancel();

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.CANCELLED);
    }

    @Test
    @DisplayName("cancel is idempotent: a retried DELETE must not become an error")
    void cancelIsIdempotent() {
        // A timed-out client retries DELETE, and the controller returns 204 both times.
        // An "already cancelled" guard here would break that.
        Flight flight = flight();

        flight.cancel();
        flight.cancel();

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.CANCELLED);
    }

    // ------------------------------------------------------------------
    // Bookability: status decides whether seats can be sold at all.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a CANCELLED flight cannot be booked and its seats do not move")
    void cancelledFlightIsNotBookable() {
        Flight flight = flight();
        flight.cancel();

        assertThatThrownBy(() -> flight.reserveSeats(1))
                .isInstanceOf(FlightNotBookableException.class)
                .hasMessageContaining("UA123")
                .hasMessageContaining("CANCELLED");

        // No partial mutation before the throw.
        assertThat(flight.getAvailableSeats()).isEqualTo(180);
    }

    @Test
    @DisplayName("a departed or arrived flight cannot be booked either")
    void flownFlightIsNotBookable() {
        for (FlightStatus status : new FlightStatus[]{FlightStatus.DEPARTED, FlightStatus.ARRIVED}) {
            Flight flight = flight();
            // Through DEPARTED, because the entity refuses SCHEDULED -> ARRIVED.
            flight.updateStatus(FlightStatus.DEPARTED);
            flight.updateStatus(status);

            assertThatThrownBy(() -> flight.reserveSeats(1))
                    .as("booking a %s flight", status)
                    .isInstanceOf(FlightNotBookableException.class);

            assertThat(flight.getAvailableSeats()).isEqualTo(180);
        }
    }

    @Test
    @DisplayName("BOARDING and DELAYED still sell seats")
    void boardingAndDelayedStayBookable() {
        // Gate sales are real and a delay is not a cancellation. "Only SCHEDULED"
        // would stop selling on every delay.
        for (FlightStatus status : new FlightStatus[]{FlightStatus.BOARDING, FlightStatus.DELAYED}) {
            Flight flight = flight();
            flight.updateStatus(status);

            flight.reserveSeats(2);

            assertThat(flight.getAvailableSeats()).as("after booking a %s flight", status).isEqualTo(178);
        }
    }

    @Test
    @DisplayName("refunds still work on a cancelled flight")
    void releaseSeatsIsNotStatusGuarded() {
        // Cancelling a flight is when seats get released, so releaseSeats must not check bookability.
        Flight flight = flight();
        flight.reserveSeats(4);
        flight.cancel();

        flight.releaseSeats(4);

        assertThat(flight.getAvailableSeats()).isEqualTo(180);
    }

    @Test
    @DisplayName("every status is classified as bookable or not")
    void everyStatusIsClassified() {
        // isBookable() is an exhaustive switch, so a new constant breaks the build.
        // This pins the current classification so a flip has to change a test too.
        assertThat(FlightStatus.values()).hasSize(6);
        assertThat(FlightStatus.SCHEDULED.isBookable()).isTrue();
        assertThat(FlightStatus.BOARDING.isBookable()).isTrue();
        assertThat(FlightStatus.DELAYED.isBookable()).isTrue();
        assertThat(FlightStatus.DEPARTED.isBookable()).isFalse();
        assertThat(FlightStatus.ARRIVED.isBookable()).isFalse();
        assertThat(FlightStatus.CANCELLED.isBookable()).isFalse();
    }

    @Test
    @DisplayName("every status is classified as taking booking cancellations or not")
    void everyStatusIsClassifiedForCancellations() {
        // Not the inverse of isBookable: a cancelled flight still takes them, because
        // refunds happen on cancelled flights. A flown one does not.
        assertThat(FlightStatus.values()).hasSize(6);
        assertThat(FlightStatus.SCHEDULED.acceptsCancellations()).isTrue();
        assertThat(FlightStatus.BOARDING.acceptsCancellations()).isTrue();
        assertThat(FlightStatus.DELAYED.acceptsCancellations()).isTrue();
        assertThat(FlightStatus.CANCELLED.acceptsCancellations()).isTrue();
        assertThat(FlightStatus.DEPARTED.acceptsCancellations()).isFalse();
        assertThat(FlightStatus.ARRIVED.acceptsCancellations()).isFalse();
    }
}
