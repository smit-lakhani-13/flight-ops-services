# 7. Spring Boot 4.1 and Java 21

Status: accepted (recorded 2026-09-22, decision taken in commit `7b45b5b`)

## Context

The service started on the then-current Boot 3.x line. Boot 4.0 shipped with
Spring Framework 7, a Jackson 3 migration and a reorganised starter set;
staying on 3.x would have been the lower-risk choice for a service already
written.

## Decision

Upgrade to Spring Boot 4.1.1 on Java 21 (`pom.xml`), including the move from
`com.fasterxml.jackson` to `tools.jackson` in the application, and the new
`spring-boot-starter-opentelemetry` observability stack.

## Consequences

* **Boot 4 manages two Jackson lines at once**: `jackson-bom.version` for
  Jackson 3 (`tools.jackson`, what the application serialises with) and
  `jackson-2-bom.version` for libraries that have not moved. That is not
  cosmetic — adding springdoc pulled swagger-core, which needs Jackson 2 at a
  higher version than Boot manages, and the fix is to override Boot's own
  property so the whole Jackson 2 line moves together. It is written up in the
  README's Versions section because the next person to add a library will meet
  it.
* Jackson 3 makes serialisation exceptions unchecked. Code that used to be
  forced to handle them now silently propagates them, so the places that still
  catch do it on purpose — see
  `src/main/java/com/smit/flightops/service/OutboxWriter.java#recordBookingCreated`.
* Spring Data's `Page` serialises in the new `{content, page{...}}` shape, which
  is what the published OpenAPI document describes. `OpenApiTest` compares the
  documented shape against a real response so the two cannot drift.
* The Lambda module deliberately stays on Jackson 2 and the AWS SDK's own
  dependencies. It is a parentless build; nothing about Boot's dependency
  management reaches it.
* Java 21 is pinned by the enforcer (`requireJavaVersion [21,22)`), so a build
  on 17 or 25 fails with a sentence rather than with a class-file version
  error two minutes later.

## Alternatives considered

* **Stay on Boot 3.5.** Supported, and it would have made the repository a
  snapshot of the previous generation. The upgrade is the kind of work the
  repository is meant to demonstrate, and it surfaced two real defects (the
  Jackson 2 conflict and the page-shape change) that were worth finding.
* **Boot 4.0.** Superseded by 4.1 during the same week; no reason to pin to it.
