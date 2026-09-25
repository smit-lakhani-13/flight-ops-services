# 8. A standalone, frameworkless Lambda consumer on arm64

Status: accepted (recorded 2026-09-22, decision taken in commit `4a9a5b9`)

## Context

Something has to consume `BookingCreated` from SQS and project it into a
read-optimised store. The obvious options were a second Spring Boot service, a
Spring Cloud Function Lambda, or a plain handler.

The consumer also has to survive receiving the same message twice, because SQS
standard queues are at-least-once by design.

## Decision

I wrote the consumer as a separate, parentless Maven module (`lambda/pom.xml`)
containing one plain `RequestHandler`
(`lambda/src/main/java/com/smit/flightops/lambda/BookingEventHandler.java`).
SAM deploys it (`lambda/template.yaml`) on the `java21` runtime, on `arm64`.

Idempotency is a conditional write: `attribute_not_exists(bookingId)` on
`PutItem`. The sort key `timestamp#bookingId` is derived entirely from the
persisted event, so a redelivery two minutes later produces an identical key.

Partial batch failure is on (`ReportBatchItemFailures`), and the handler
returns only the ids that failed.

## Consequences

* **No shared jar.** A shared event library would force the consumer to be
  rebuilt and redeployed in lockstep with the producer, and the queue exists to
  remove that coupling. Both sides test against one file,
  `contracts/booking-created-v1.json`, and neither test imports the other
  side's code.

* No framework means no container to start on a cold invocation. The handler
  does one thing, and a dependency-injection container would be pure cold-start
  cost.

* `arm64` is cheaper per GB-second than x86, and the workload has no native
  dependencies. The jar is pure bytecode, with `url-connection-client` as its
  HTTP client, so arm64 costs nothing to adopt. The *service* image is
  different: it ships an OS and must be built for the nodes' amd64. The README
  documents that asymmetry.

* **One named HTTP client.** The SDK brings in two HTTP clients transitively,
  `netty-nio-client` and `apache5-client`. I exclude both in favour of
  `url-connection-client`. The pom also excludes `apache-client`, which is not
  transitive at 2.55.x, so an SDK bump that brings it back cannot slip in.
  The client holder also names `UrlConnectionHttpClient`. Left to discovery,
  the SDK ranks Apache 5 above URLConnection, so a dependency that brought
  Apache 5 back would take over.

* A permanent failure (malformed JSON, or a `seats` value that is not a whole
  number of at least 1) is still reported, so it reaches the DLQ and is not
  lost. Two retries are wasted, and an event is preserved. Splitting
  parse failures onto a quarantine queue is the production refinement, and I
  have not done it here.

**Correction (2026-09-25).** Two of the bullets above changed on 23 September
without a note. The arm64 bullet used to say that a local `docker build` on
Apple Silicon matches the deployment target. The Lambda is a jar, not an image,
so that reason never applied. The HTTP-client bullet used to say that the SDK
brings in three HTTP clients, and that naming the client matters because jar
ordering in a shaded jar would otherwise decide. It brings two, and the SDK
picks by a fixed priority that ranks Apache 5 above URLConnection.

## Alternatives considered

* **Spring Cloud Function.** Familiar programming model, a container start per
  cold invocation, and a dependency tree an order of magnitude larger.

* **A second Spring Boot service.** It would consume SQS around the clock, an
  always-on cost for a workload that is bursty and small.

* **A shared `events` module.** Compile-time safety, bought with deployment
  coupling. I rejected it for the reason above.
