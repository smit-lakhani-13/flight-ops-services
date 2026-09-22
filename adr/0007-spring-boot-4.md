# 7. Spring Boot 4.1 and Java 21

Status: accepted (recorded 2026-09-22, decision taken in commit `7b45b5b`)

## Context

The service started on the then-current Boot 3.x line. Boot 4.0 shipped with
Spring Framework 7, a Jackson 3 migration and a reorganised starter set.
Staying on 3.x would have been the lower-risk choice for a service already
written.

## Decision

I upgraded to Spring Boot 4.1.1 on Java 21 (`pom.xml`). That included the move
from `com.fasterxml.jackson` to `tools.jackson` in the application, and the new
`spring-boot-starter-opentelemetry` observability stack.

## Consequences

* **Two Jackson lines.** Boot 4 manages both at once: `jackson-bom.version` for
  Jackson 3 (`tools.jackson`, which the application serialises with) and
  `jackson-2-bom.version` for libraries that have not moved. This has real
  effects. Adding springdoc pulled in swagger-core, which needs Jackson 2 at a
  higher version than Boot manages. The fix is to override Boot's own property
  so the whole Jackson 2 line moves together. The README's Versions section
  covers it, because the next person to add a library will meet it.

* Jackson 3 makes serialisation exceptions unchecked. Code that used to be
  forced to handle them now lets them propagate with no compiler error. Each
  place that still catches one does it for a stated reason (see
  `src/main/java/com/smit/flightops/service/OutboxWriter.java#recordBookingCreated`).

* Spring Data's `Page` serialises in the new `{content, page{...}}` shape, and
  the published OpenAPI document describes that shape. `OpenApiTest` compares
  the documented shape against a real response, so the two cannot drift.

* The Lambda module stays on Jackson 2 and the AWS SDK's own dependencies. It
  is a parentless build, and nothing about Boot's dependency management reaches
  it.

* The enforcer pins Java 21 (`requireJavaVersion [21,22)`). A build on 17 or 25
  fails at once with a readable message. Without the pin it would fail two
  minutes later with a class-file version error.

## Alternatives considered

* **Stay on Boot 3.5.** Supported, and it would have made the repository a
  snapshot of the previous generation. The upgrade is the kind of work I want
  the repository to show. It also surfaced two real defects: the Jackson 2
  conflict and the page-shape change.

* **Boot 4.0.** Superseded by 4.1 during the same week, so there was no reason
  to pin to it.
