# 8. A standalone, frameworkless Lambda consumer on arm64

Status: accepted (recorded 2026-09-22, decision taken in commit `4a9a5b9`)

## Context

Something has to consume `BookingCreated` from SQS and project it into a
read-optimised store. The obvious options were a second Spring Boot service, a
Spring Cloud Function Lambda, or a plain handler.

The consumer also has to survive being redelivered the same message, because
SQS standard queues are at-least-once by design.

## Decision

A separate, **parentless** Maven module (`lambda/pom.xml`) containing one plain
`RequestHandler`
(`lambda/src/main/java/com/smit/flightops/lambda/BookingEventHandler.java`),
deployed by SAM (`template.yaml`) on the `java21` runtime, `arm64`.

Idempotency is a conditional write: `attribute_not_exists(bookingId)` on
`PutItem`, with the sort key `timestamp#bookingId` derived entirely from the
persisted event so a redelivery two minutes later produces an identical key.

Partial batch failure is on (`ReportBatchItemFailures`), and the handler
returns only the ids that failed.

## Consequences

* **No shared jar with the producer.** That is the point: a shared event
  library means the consumer must be rebuilt and redeployed in lockstep with
  the producer, which is the coupling the queue exists to remove. The contract
  is enforced by a file both sides test against —
  `contracts/booking-created-v1.json` — and neither test imports the other
  side's code.
* No framework means no container to start on a cold invocation. The handler
  does one thing; a dependency-injection container would be pure cold-start
  cost.
* `arm64` is cheaper per GB-second than x86 and the workload has no native
  dependencies. It also means a local `docker build` on an Apple Silicon
  machine matches the deployment target, while the *service* image does not —
  that asymmetry is documented in the README.
* The SDK drags in three HTTP clients transitively; all three are excluded in
  favour of `url-connection-client`. Naming the HTTP client explicitly rather
  than letting the SDK discover it through `META-INF/services` matters in a
  shaded jar, where jar ordering would otherwise decide.
* A permanent failure (malformed JSON) is still reported, so it reaches the
  DLQ rather than being silently deleted. Two retries are wasted; an event is
  preserved. Splitting parse failures onto a quarantine queue is the
  production refinement, and it is not done here.

## Alternatives considered

* **Spring Cloud Function.** Familiar programming model, a container start per
  cold invocation, and a dependency tree an order of magnitude larger.
* **A second Spring Boot service consuming SQS.** Always-on cost for a workload
  that is bursty and small.
* **A shared `events` module.** Compile-time safety, bought with deployment
  coupling. Rejected; see above.
