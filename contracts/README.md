# Event contracts

One file per event, and the file is the contract.

`flight-ops-service` publishes `BookingCreated` to SQS; the Lambda in
`lambda/` consumes it. They are separate Maven builds with no shared
module, so nothing about that agreement is checked by a compiler — the
producer could rename `flightNumber` to `flight_number` and both
projects would build, both test suites would stay green, and the break
would surface in production as a Lambda that writes rows with a null
partition key.

The two modules could have shared a JAR, and deliberately do not. A
shared event library means the consumer has to be rebuilt and redeployed
in lockstep with the producer, which is precisely the coupling the queue
between them exists to remove. The cost of independent deployment is
that the contract has to be enforced somewhere else, and this directory
is that somewhere.

## How it is enforced

Two tests, one on each side, both reading this same file:

| Side     | Test                                                      | What it proves |
|----------|-----------------------------------------------------------|----------------|
| Producer | `BookingEventContractTest` (app module)                   | What the service serialises has exactly these field names, no more and no fewer, with these JSON types |
| Consumer | `BookingEventContractTest` (`lambda/`)                     | The Lambda parses this document into a fully populated `BookingEvent`, and still parses it when the producer adds a field it does not know about |

Neither test imports the other side's code. The only thing they share is
this file, which is the point: if the producer changes its record, the
producer's test fails here and now, in its own build, and the author is
told in the same commit that they are about to break a consumer.

## What travels beside the body

`booking-created-v1.json` is the body, and only the body. Two SQS message
attributes ride alongside it — `eventType`, so a consumer can route or
filter without parsing the payload, and `traceparent`, the W3C trace
context of the HTTP request that made the booking, captured by
`OutboxWriter` at booking time and carried on the outbox row until
`SqsEventPublisher` sends it. `BookingEventHandler` logs the traceparent
it receives, which is what lets an engineer holding a trace id from an
API response find that booking's projection in a different process on
the far side of a queue.

Neither attribute is in the contract file, and that is the decision, not
an omission. The body is what the consumer's correctness depends on, so
it is pinned field by field on both sides. Metadata is not: a booking
made outside a traced request has no trace context, the service refuses
to invent one, and the attribute is simply absent. A consumer that
required it would reject perfectly good events. So the Lambda reads it
null-safely at three separate levels — no attribute map at all, no
`traceparent` key, a `traceparent` sent with a binary data type — and
validates the value against the W3C shape before it goes anywhere near a
log line, because an attribute is attacker-influenced input and a
newline in it forges a CloudWatch entry that reads exactly like a real
one. A missing or malformed trace never fails a projection. See
`BookingEventHandlerTest#theProducersTraceReachesTheLog` and
`#aHostileTraceparentIsIgnored`, and `events/sqs-with-trace.json`, which
carries one message with a trace and one without.

## Changing a contract

Additive changes — a new field — are safe in one direction only. The
consumer disables `FAIL_ON_UNKNOWN_PROPERTIES`, so it tolerates fields
it has never heard of; its contract test pins that tolerance explicitly
rather than leaving it to a default somebody might tidy up. So: add the
field to the producer, update this file, deploy the producer, and the
consumer keeps working untouched.

Removals and renames are not safe in any order, because the consumer in
production is the version deployed before the change, not the version in
the repository. They need a new file — `booking-created-v2.json` — with
both versions published until the old consumer is gone. That is more
work than editing this file, which is the intended discomfort: the
ordering of those deploys is the thing that actually breaks, and it
should be hard to do by accident.
