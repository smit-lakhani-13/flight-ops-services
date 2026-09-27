-- flights.version is NOT NULL, and a row that leaves it out starts at 0.
--
-- V1 declared the column as a bare BIGINT: nullable, no default. Every row the
-- application writes is fine, because a JPA persist seeds @Version at 0. A row
-- written any other way is not. DataSeeder's Javadoc says reference data in a
-- real environment belongs in a migration, and a plain INSERT there would
-- naturally leave out a column that belongs to the ORM. That row gets NULL,
-- and Hibernate cannot increment a null version: the first booking, booking
-- cancellation, status change or flight cancel on that flight fails at flush
-- with a 500, and so does every one after it, until someone finds the column
-- and backfills it. ADR 0002 says version does real work on the paths that
-- load the flight without a row lock, so the schema should guarantee it.
--
-- DEFAULT 0 is what protects the plain INSERT. NOT NULL turns an explicit
-- NULL into an error at write time, instead of a flight that can never be
-- written again. V2's reason applies here too: a later migration, a support
-- script or a second service bypasses the entity completely.
--
-- The UPDATE matches no row in any database this application has written, but
-- it keeps the migration correct for one that already holds such a row. SET
-- NOT NULL scans the table under an ACCESS EXCLUSIVE lock, which is nothing on
-- a table this size.
--
-- Flight maps the column with nullable = false and @ColumnDefault("0"), so the
-- create-drop H2 schema says the same thing.

UPDATE flights SET version = 0 WHERE version IS NULL;

ALTER TABLE flights
    ALTER COLUMN version SET DEFAULT 0,
    ALTER COLUMN version SET NOT NULL;
