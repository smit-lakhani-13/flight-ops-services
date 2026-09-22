-- Invariants that were only enforced in Java.
--
-- Flight.reserveSeats refuses to oversell and Flight.releaseSeats clamps to
-- totalSeats, but both live in one method on one code path. Anything that
-- writes these tables another way - a later migration, a support script, a
-- second service, psql at 2am - bypasses them completely. V1 declared five
-- constraints and not one was a check, so the seat floor this whole service is
-- about was the one rule the schema did not know.
--
-- On a large table this would be two deployments, not one migration: ADD
-- CONSTRAINT ... CHECK takes an ACCESS EXCLUSIVE lock and scans every row
-- before it returns, so a busy table is unavailable for the duration. The
-- pattern there is ADD ... NOT VALID first (a fast metadata-only change that
-- starts enforcing on new writes), then VALIDATE CONSTRAINT in a separate
-- migration, which takes only SHARE UPDATE EXCLUSIVE and lets writes continue.
-- Doing both in one transaction, as a single Flyway migration would, gains
-- nothing at all: the ACCESS EXCLUSIVE lock from the ADD is held until commit
-- either way. These tables are small and this project has no live data, so the
-- straightforward form is the honest one here - but the two-step is the answer
-- for a table with millions of rows.

ALTER TABLE flights
    ADD CONSTRAINT ck_flights_seat_floor CHECK (available_seats >= 0),
    ADD CONSTRAINT ck_flights_seat_ceiling CHECK (available_seats <= total_seats),
    ADD CONSTRAINT ck_flights_capacity CHECK (total_seats > 0),
    -- A flight from EWR to EWR is a data-entry error, not a route. Rejected in
    -- FlightService too, for a 400 with a readable message; this is the floor.
    ADD CONSTRAINT ck_flights_distinct_endpoints CHECK (origin <> destination);

ALTER TABLE bookings
    ADD CONSTRAINT ck_bookings_seats_positive CHECK (seats > 0);
