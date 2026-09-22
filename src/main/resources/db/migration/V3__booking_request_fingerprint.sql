-- Idempotency-key misuse.
--
-- uk_bookings_idempotency_key guarantees one booking per key. It does not
-- notice a client that reuses a key for a DIFFERENT booking: that request used
-- to get 201 and somebody else's booking back - the wrong answer, with a
-- success status, on a money path. The only thing the service compared was the
-- key. Storing a hash of the request lets the replay path tell a genuine retry
-- from a collision; see BookingRequest.fingerprint().
--
-- Deliberately nullable, and this is the interesting part. Rows written before
-- this column existed have no fingerprint and there is nothing truthful to
-- backfill, so NULL means "cannot compare" and BookingService treats such a row
-- as a plain replay - the old behaviour, for old rows only. Adding the column
-- NOT NULL with a sentinel default would have meant inventing a hash for data
-- nobody hashed, and every later reader would have had to know the sentinel.
-- A nullable column says the same thing in the type system instead of in a
-- comment somebody will miss.

ALTER TABLE bookings
    ADD COLUMN request_fingerprint VARCHAR(64);

COMMENT ON COLUMN bookings.request_fingerprint IS
    'SHA-256 of the normalised booking request. NULL for rows created before V3.';
