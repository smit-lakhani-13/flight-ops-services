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
