package com.smit.flightops.entity;

import com.smit.flightops.exception.FlightNotBookableException;
import com.smit.flightops.exception.InsufficientSeatsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests on the aggregate. No Spring, no database — the seat invariant
 * is a domain rule, and it should be provable without either.
 */
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
    @DisplayName("cancel is idempotent — a retried DELETE must not become an error")
    void cancelIsIdempotent() {
        // DELETE is required to be idempotent, and a client that times out will
        // retry. Cancelling twice has to be a no-op rather than a 409, so this
        // pins it: adding a "already cancelled" guard here would break the API
        // contract that the controller relies on to return 204 both times.
        Flight flight = flight();

        flight.cancel();
        flight.cancel();

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.CANCELLED);
    }

    // ------------------------------------------------------------------
    // Bookability. Found by probing a running instance, not by reading code:
    // DELETE /api/v1/flights/UA123 -> 204 CANCELLED, then POST /bookings ->
    // 201 and availableSeats 180 -> 176. The entity guarded the seat count and
    // nothing else.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION: a CANCELLED flight cannot be booked and its seats do not move")
    void cancelledFlightIsNotBookable() {
        Flight flight = flight();
        flight.cancel();

        assertThatThrownBy(() -> flight.reserveSeats(1))
                .isInstanceOf(FlightNotBookableException.class)
                .hasMessageContaining("UA123")
                .hasMessageContaining("CANCELLED");

        // The half that actually matters: no partial mutation before the throw.
        assertThat(flight.getAvailableSeats()).isEqualTo(180);
    }

    @Test
    @DisplayName("a departed or arrived flight cannot be booked either")
    void flownFlightIsNotBookable() {
        for (FlightStatus status : new FlightStatus[]{FlightStatus.DEPARTED, FlightStatus.ARRIVED}) {
            Flight flight = flight();
            // Reached through DEPARTED rather than set directly, because
            // SCHEDULED -> ARRIVED is no longer a transition the entity allows:
            // a flight cannot arrive somewhere it never left. This loop used to
            // jump straight to ARRIVED, which the transition graph caught the
            // moment it was added - a test setting up a state the domain says
            // is impossible. Two steps say the same thing truthfully.
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
        // Gate sales are real, and a delay is not a cancellation. Asserted so
        // that "guard the booking path" is never over-tightened into
        // "only SCHEDULED", which would silently stop selling on every delay.
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
        // The mirror of the guard above. Cancelling a flight is exactly when
        // seats get released, so releaseSeats must NOT check bookability.
        Flight flight = flight();
        flight.reserveSeats(4);
        flight.cancel();

        flight.releaseSeats(4);

        assertThat(flight.getAvailableSeats()).isEqualTo(180);
    }

    @Test
    @DisplayName("every status is classified as bookable or not")
    void everyStatusIsClassified() {
        // isBookable() is an exhaustive switch with no default, so a new constant
        // breaks the build rather than defaulting to bookable. This test only
        // pins the current classification so a flip is deliberate.
        assertThat(FlightStatus.values()).hasSize(6);
        assertThat(FlightStatus.SCHEDULED.isBookable()).isTrue();
        assertThat(FlightStatus.BOARDING.isBookable()).isTrue();
        assertThat(FlightStatus.DELAYED.isBookable()).isTrue();
        assertThat(FlightStatus.DEPARTED.isBookable()).isFalse();
        assertThat(FlightStatus.ARRIVED.isBookable()).isFalse();
        assertThat(FlightStatus.CANCELLED.isBookable()).isFalse();
    }
}
