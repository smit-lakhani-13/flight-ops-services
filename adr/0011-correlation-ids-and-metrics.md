# 11. Correlation ids, three domain counters, and no exporter

Status: accepted (recorded 2026-09-22, decision taken in commit `d18f58b`)

## Context

The README's own "still open" list named the largest operability gap: plain
text logs with nothing joining the lines of one request together, and no metric
that answers a question about bookings. `http_server_requests` counts requests
by URI and status, which cannot distinguish a real booking from an idempotent
replay — both are `201` on the same URI.

The temptation with observability is to add everything at once: an exporter, a
collector, dashboards. Most of that is infrastructure this deployment does not
have, and configuration pointing at a collector that does not exist is worse
than none — it produces connection-refused noise every minute forever.

## Decision

Three things, and deliberately not a fourth:

1. **A correlation id on every response.**
   `src/main/java/com/smit/flightops/observability/RequestIdFilter.java` runs at
   `HIGHEST_PRECEDENCE`, ahead of Spring Security, accepts an inbound
   `X-Request-Id` matching `^[A-Za-z0-9._:-]{1,128}$`, mints a UUID otherwise,
   puts it in the MDC and echoes it on **every** response including 401 and 403.
2. **Trace and span ids in the log pattern**, from
   `spring-boot-starter-opentelemetry`, so a log line reads
   `[flight-ops-service,<traceId>,<spanId>,<requestId>]`.
3. **Domain counters** that HTTP metrics cannot express, in
   `src/main/java/com/smit/flightops/observability/BookingMetrics.java` and
   `src/main/java/com/smit/flightops/observability/OutboxMetrics.java`.

No OTLP exporter endpoint is configured.

## Consequences

* A 401 carries an id, which is exactly the response people ring up about. A
  filter ordered after the security chain would have left those bare — Spring
  Security rejects before `DispatcherServlet` is reached.
* **An inbound id is validated, not trusted.** It lands in a log line and in a
  response header, so an unchecked value is two injection sinks: `\r\n` forges
  a second log entry or a second header. A malformed diagnostic header is not
  a reason to fail somebody's booking, so it is replaced silently.
* The id is not in the error body. `ErrorResponse` is a published contract, and
  the id is already on the same response as a header.
* **The starter's two halves have opposite defaults**, which is worth knowing
  before deploying it: traces are opt-in (no exporter until an endpoint is
  set), metrics are opt-out (`micrometer-registry-otlp` is a *push* registry
  that starts publishing to `localhost:4318` with nothing listening). The
  first run after adding the starter logged exactly that, and
  `application.yml` now disables OTLP metrics explicitly.
* Structured logging is on for the `prod` profile only
  (`logging.structured.format.console: ecs`). The default profile is somebody
  reading output with their eyes.
* Nothing scrapes Prometheus in this deployment, so the meters are evidence
  rather than alerting. That is stated in the README rather than implied away.

## Alternatives considered

* **`logstash-logback-encoder`.** The usual answer, and now redundant: Boot 4
  ships structured logging with MDC inclusion built in, so the dependency would
  add a second way to do the same thing.
* **Adding an OTLP collector to the deployment.** Real value, real cost, and
  nothing in the demo it makes possible that the log ids do not already answer.
* **Tagging the booking counters by flight number.** Unbounded cardinality, a
  Prometheus outage waiting for a busy day.
