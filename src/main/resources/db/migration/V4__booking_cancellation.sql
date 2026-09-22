-- Booking cancellation, which is what gives Flight.releaseSeats a caller.
--
-- releaseSeats existed, was tested, and nothing in the application ever called
-- it: there was no way to cancel a booking, so seats could only ever leave
-- inventory. Dead code on the refund path is worse than no code, because it
-- reads as though the feature is there.
--
-- Soft cancellation, not DELETE, for the same reason flights are soft-
-- cancelled: the row is the record that the seats were once sold, and a
-- cancelled booking must keep its idempotency key. Replaying the original key
-- has to return the cancelled booking rather than quietly creating a second
-- one, and a deleted row would free the key for exactly that.
--
-- cancelled_at is also the idempotency guard for the release itself. Cancelling
-- twice must not credit the seats twice; the second call sees a non-NULL value
-- and does nothing. Flight.releaseSeats clamps to total_seats, which bounds the
-- damage but does not prevent it - a double cancel on a 180-seat flight with
-- 100 sold would still invent 2 seats. The timestamp is the real fix, and
-- ck_flights_seat_ceiling from V2 is the floor under it.

ALTER TABLE bookings
    ADD COLUMN cancelled_at TIMESTAMP(6) WITH TIME ZONE;

COMMENT ON COLUMN bookings.cancelled_at IS
    'When the booking was cancelled and its seats returned. NULL means active.';

-- Partial index: every query that cares about this column asks for the active
-- bookings, and on a mature table the overwhelming majority of rows are active
-- - so an index over the whole column would be almost entirely dead weight.
CREATE INDEX idx_bookings_active ON bookings (flight_id) WHERE cancelled_at IS NULL;
