-- flights.status holds a FlightStatus constant and nothing else.
--
-- V1 declared the column as VARCHAR(255) NOT NULL, and V2 gave four other
-- flight invariants a check but not this one. Hibernate's create-drop schema
-- declares an EnumType.STRING column on H2 as an ENUM of the constants, so no
-- test could store any other value, while PostgreSQL stored anything. A
-- support script that writes 'CANCELED', or 'cancelled' in lower case, would
-- break every read of that flight: Hibernate cannot convert the value back to
-- a FlightStatus, so GET /api/v1/flights/{flightNumber}, any list page that
-- holds the row, the bookings list that fetches it and every new booking on
-- it answer 500. The list below is exact and upper case, as Hibernate reads
-- it, so both of those are refused at write time, where the mistake is made.
--
-- ck_flights_status follows V2's naming. A new FlightStatus constant needs a
-- migration that drops and re-adds this check (FlightStatus's Javadoc says
-- so). Flyway runs at startup, so that migration ships with the code that
-- first writes the new value.
--
-- Written as the two steps V2's header gives for a large table: ADD ... NOT
-- VALID, which only changes the catalogue and enforces the check on every
-- write from then on, then VALIDATE CONSTRAINT, which checks the rows already
-- there. Here both run in one transaction, and as V2 says, that gains
-- nothing: the ACCESS EXCLUSIVE lock the ADD takes is held until commit,
-- through the scan. On a table this size that costs nothing, and a row that
-- breaks the rule fails the VALIDATE with an error that names the check and
-- rolls both statements back. On a large table, move the VALIDATE to a
-- migration of its own, where it takes only SHARE UPDATE EXCLUSIVE and reads
-- and writes carry on.

ALTER TABLE flights
    ADD CONSTRAINT ck_flights_status
    CHECK (status IN ('SCHEDULED', 'BOARDING', 'DEPARTED', 'ARRIVED',
                      'CANCELLED', 'DELAYED'))
    NOT VALID;

ALTER TABLE flights VALIDATE CONSTRAINT ck_flights_status;
