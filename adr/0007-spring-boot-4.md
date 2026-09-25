# 7. Spring Boot 4.1 and Java 21

Status: accepted (recorded 2026-09-22, decision taken in commit `7b45b5b`)

## Context

The service started on Spring Boot 3.5.16, in the first commit on 15 September
2026. That was the last open-source 3.5 patch: Boot 3.5 had left open-source
support on 30 June 2026. Boot 4.1.0 had been the current line since 10 June
2026. Boot 4 brought Spring Framework 7, a Jackson 3 migration and a
reorganised starter set. Staying on 3.5 looked like the lower-risk choice for a
service already written, but it would get no more free patches.

## Decision

On 22 September I upgraded to Spring Boot 4.1.1 on Java 21 (`pom.xml`). That
included the move from `com.fasterxml.jackson` to `tools.jackson` in the
application. The `spring-boot-starter-opentelemetry` observability stack came
later, in `d18f58b` (see [ADR 0011](0011-correlation-ids-and-metrics.md)).

## Consequences

* **Two Jackson lines.** Boot 4 manages both at once: `jackson-bom.version` for
  Jackson 3 (`tools.jackson`, which the application serialises with) and
  `jackson-2-bom.version` for libraries that have not moved. This has real
  effects. Adding springdoc pulled in swagger-core, which needs Jackson 2 at a
  higher version than Boot manages. The fix is to override Boot's own property
  so the whole Jackson 2 line moves together. The Versions section of
  [CONTRIBUTING.md](../CONTRIBUTING.md#versions) covers it, because the next
  person to add a library will meet it.

* Jackson 3 makes serialisation exceptions unchecked. Code that used to be
  forced to handle them now lets them propagate with no compiler error. Each
  place that still catches one does it for a stated reason (see
  `src/main/java/com/smit/flightops/service/OutboxWriter.java#recordBookingCreated`).

* The Lambda module stays on Jackson 2 and the AWS SDK's own dependencies. It
  is a parentless build, and nothing about Boot's dependency management reaches
  it.

* The enforcer pins Java 21 (`requireJavaVersion [21,22)`). A build on 17 or 25
  fails at once with a readable message. Without the pin, a build on 17 would
  fail later, in the compiler, on `release version 21 not supported`.

## Alternatives considered

* **Stay on Boot 3.5.** Its open-source support ended on 30 June 2026, so only
  commercial support was left. Staying would also have made the repository a
  snapshot of the previous generation. The upgrade is the kind of work I want
  the repository to show. It needed renames of dependencies, packages and
  types, and [the defect log](../doc/DEFECT-LOG.md) lists them. The Jackson 2
  conflict came later, when springdoc was added in `82ea9b4`, and the enforcer
  caught it.

* **Boot 4.0.** 4.1.0 had been the current line since 10 June 2026, three
  months before the first commit, so there was no reason to pin to the older
  minor.

**Correction (2026-09-23).** This record used to say the service started on the
then-current 3.x line, that 3.5 was supported, and that 4.1 superseded 4.0
during the same week. Boot 3.5 left open-source support on 30 June 2026, and
4.1.0 shipped on 10 June 2026, both before the first commit on 15 September.
It also listed the `{content, page{...}}` page shape as a consequence of
Boot 4, and said the upgrade surfaced the Jackson 2 conflict. The first commit
already set that page shape on 3.5.16, and the Jackson 2 conflict came with
springdoc in `82ea9b4`, after the upgrade. The decision stands, on a stronger
reason.
