-- Flight inventory and bookings. PostgreSQL.
--
-- Hibernate runs with ddl-auto: validate against this schema, so column names
-- and types here must match what the entities imply. If they drift, startup
-- fails loudly instead of corrupting data at runtime.

CREATE TABLE flights
(
    id              BIGSERIAL PRIMARY KEY,
    flight_number   VARCHAR(10)                 NOT NULL,
    origin          VARCHAR(3)                  NOT NULL,
    destination     VARCHAR(3)                  NOT NULL,
    total_seats     INTEGER                     NOT NULL,
    available_seats INTEGER                     NOT NULL,
    status          VARCHAR(255)                NOT NULL,
    departure_time  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    version         BIGINT,
    -- The entity also declares @Index(name = "idx_flight_number", unique = true)
    -- on this column. That is only used by ddl-auto: create-drop on H2; here the
    -- UNIQUE constraint already creates the index, and adding a second one would
    -- double the write cost for no read benefit. Hibernate's validate does not
    -- compare indexes, so the two schemas do not disagree.
    CONSTRAINT uk_flights_flight_number UNIQUE (flight_number)
);

-- Covers the search endpoint's route lookup.
CREATE INDEX idx_origin_dest ON flights (origin, destination);

CREATE TABLE bookings
(
    id              BIGSERIAL PRIMARY KEY,
    flight_id       BIGINT                      NOT NULL,
    passenger_name  VARCHAR(255)                NOT NULL,
    seats           INTEGER                     NOT NULL,
    idempotency_key VARCHAR(255)                NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    -- This constraint is the actual exactly-once guarantee. The application
    -- check in BookingService is only an optimisation: under a true race, two
    -- transactions both pass the check and the database rejects the loser.
    CONSTRAINT uk_bookings_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT fk_bookings_flight FOREIGN KEY (flight_id) REFERENCES flights (id)
);

CREATE INDEX idx_bookings_flight_id ON bookings (flight_id);
