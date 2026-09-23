# Event contracts

One file per event, and the file is the contract.

`flight-ops-service` publishes `BookingCreated` to SQS; the Lambda in
`lambda/` consumes it. They are separate Maven builds with no shared
module, so no compiler checks that agreement. The producer could rename
`flightNumber` to `flight_number` and both projects would build and both
test suites would stay green. The break would surface only in production,
as every message failing to parse and draining into the dead-letter queue
after three receives.

The one mercy is that the failure is loud, and it names the field. A
renamed field binds to `null`, and the `BookingEvent` record's constructor
rejects it before any DynamoDB call is built. The Lambda logs
`booking event is missing a value for 'flightNumber'`, reports the message
as a batch-item failure, and after three receives the message is in the
DLQ. A missing `seats` fails the same way in Jackson. Nothing is written,
so the projection stops instead of filling with unusable items. What it
costs is every booking event on the floor until someone reads the DLQ.
`BookingEventHandlerTest#aMissingKeyFieldNamesItself` pins this.

I chose not to share a JAR between the two modules. A shared event library
means the consumer has to be rebuilt and redeployed in lockstep with the
producer, which is the coupling the queue between them exists to remove.
The cost of independent deployment is that the contract has to be enforced
somewhere else, and this directory is where.

## How it is enforced

Two tests, one on each side, both reading this same file:

| Side     | Test                                                      | What it proves |
|----------|-----------------------------------------------------------|----------------|
| Producer | `BookingEventContractTest` (app module)                   | What the service serialises has these field names, no more and no fewer, with these JSON types |
| Consumer | `BookingEventContractTest` (`lambda/`)                     | The handler's own mapper parses this document into a fully populated `BookingEvent`, and still parses it when the producer adds a field it does not know about |

Neither test imports the other side's code, and the only thing they share
is this file. If the producer changes its record, the producer's test fails
in its own build, and the author learns in the same commit that they are
about to break a consumer.

## What travels beside the body

`booking-created-v1.json` is the body, and only the body. Two SQS message
attributes ride alongside it. `eventType` lets a consumer route or filter
without parsing the payload. `traceparent` is the W3C trace context of the
HTTP request that made the booking, captured by `OutboxWriter` at booking
time and carried on the outbox row until `SqsEventPublisher` sends it.
`BookingEventHandler` logs the traceparent it receives, so an engineer
holding a trace id from an API response can find that booking's projection
in a different process on the far side of a queue.

I kept both attributes out of the contract file. The body is what the
consumer's correctness depends on, so it is pinned field by field on both
sides. Metadata is not: a booking made outside a traced request has no
trace context, the service refuses to invent one, and the attribute is
absent. A consumer that required it would reject good events. So the
Lambda reads it null-safely, because there may be no attribute map at all,
no `traceparent` key, or a `traceparent` sent with a binary data type. It
matches the value against the W3C shape before it goes near a log line.

An attribute is input from whoever can send to the queue. A newline in it
would put a fabricated line inside the log entry, and an unbounded one
would be shipped to CloudWatch on every invocation. Values from the body
come from the same senders. Before the handler logs a booking id or an
exception message, it replaces control, format and line-separator
characters with `?` and caps the text at 1,000 characters. A missing or
malformed trace never fails a projection. See `BookingEventHandlerTest#theProducersTraceReachesTheLog`
and `#aHostileTraceparentIsIgnored`, and `events/sqs-with-trace.json`,
which carries one message with a trace and one without.

## Changing a contract

Additive changes (a new field) are safe in one direction only. The
consumer disables `FAIL_ON_UNKNOWN_PROPERTIES`, so it tolerates fields
it has never heard of, and its contract test pins that tolerance instead
of leaving it to a default someone might tidy up. So: add the field to
the producer, update this file, deploy the producer, and the consumer
keeps working untouched.

Removals and renames are not safe in any order, because the consumer in
production is the version deployed before the change, not the version in
the repository. They need a new file, `booking-created-v2.json`, with
both versions published until the old consumer is gone. That is more
work than editing this file, and the discomfort is intended: the
ordering of those deploys is the thing that breaks, and it should be
hard to do by accident.
