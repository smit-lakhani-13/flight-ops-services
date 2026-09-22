-- The transactional outbox.
--
-- Before this table, BookingWriter.insertNewBooking called the SQS client
-- directly, inside its own transaction, and that has three failure modes which
-- cannot all be fixed at once by moving the call:
--
--   send inside the transaction : a slow queue holds the flight row lock, and
--                                 a send failure rolls a perfectly good
--                                 booking back
--   send before the commit      : the queue gets an event for a booking that
--                                 then rolls back - a consumer projects a
--                                 reservation that does not exist
--   send after the commit       : the process can die in the gap, and the
--                                 booking exists with no event, forever
--
-- There is no ordering of "write to the database" and "call another system"
-- that is atomic, because they are two systems. The outbox removes the second
-- system from the transaction entirely: the event is an INSERT into this
-- table, in the same transaction as the booking, so it commits or rolls back
-- with it, and a separate poller moves it to SQS afterwards. What that buys is
-- exactly-once *recording* and at-least-once *delivery*, which is the strongest
-- pair available without distributed transactions - and the reason the Lambda
-- consumer's DynamoDB write is a conditional put rather than an append.

CREATE TABLE outbox_events (
    id             BIGSERIAL PRIMARY KEY,

    -- What the event is about. Not used for routing today; it is here because
    -- the alternative is discovering, the first time a second aggregate needs
    -- events, that every row has to be backfilled to find out which ones were
    -- bookings.
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(50)  NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,

    -- The exact bytes that go on the wire, serialised once at the moment the
    -- booking happened. Deliberately not re-derived from the booking row at
    -- publish time: the row can change between the booking and the publish (a
    -- cancellation, say), and an event that describes the aggregate's state
    -- now rather than the state it had when the event occurred is not an event
    -- at all.
    payload        TEXT         NOT NULL,

    created_at     TIMESTAMP(6) WITH TIME ZONE NOT NULL,

    -- NULL means unpublished. Deliberately a nullable timestamp rather than a
    -- boolean `published` flag: the timestamp answers "has it been sent?" and
    -- "how far behind is the poller?" with one column, and lag is the number
    -- that actually gets alerted on.
    published_at   TIMESTAMP(6) WITH TIME ZONE,

    -- Bookkeeping for the rows that keep failing. A row with a high attempt
    -- count and a last_error is the thing an operator needs to see; without
    -- them a stuck event is invisible until someone asks why a booking never
    -- reached the projection.
    attempts       INTEGER      NOT NULL DEFAULT 0,
    last_error     VARCHAR(500)
);

-- Partial index, and the WHERE clause is the whole point.
--
-- The poller only ever asks for unpublished rows, and in a healthy system that
-- is a handful out of everything ever published. A full index on published_at
-- would grow without bound and the planner would still have to skip past
-- millions of published rows; this one only contains the rows that are still
-- pending, so it stays small enough to sit in cache no matter how long the
-- table gets. Ordered by id so the index also satisfies the poller's ORDER BY.
CREATE INDEX idx_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;
