# 11. Correlation ids, domain counters, and no exporter

Status: accepted (recorded 2026-09-22, decision taken in commit `d18f58b`; the
outbox counters and gauges in `OutboxMetrics` followed in `a6efc1c`)

## Context

When I took this decision, the README's "still open" list named the largest
operability gap. Logs were plain text with nothing joining the lines of one
request together, and no metric answered a question about bookings.
`http_server_requests` counts requests by URI and status. It cannot tell a real
booking from an idempotent replay, because both are a `201` on the same URI.

The temptation with observability is to add everything at once: an exporter, a
collector, dashboards. Most of that is infrastructure this repository does not
have. Configuration pointing at a collector that does not exist is worse than
none, because it produces connection-refused noise every minute forever.

## Decision

I added these, and stopped short of an exporter:

1. **A correlation id.**
   `src/main/java/com/smit/flightops/observability/RequestIdFilter.java` runs
   at `HIGHEST_PRECEDENCE`, ahead of Spring Security. It accepts an inbound
   `X-Request-Id` matching `^[A-Za-z0-9._:-]{1,128}$` and mints a UUID
   otherwise. It puts the id in the MDC and echoes it on every response the
   application handles, 401 and 403 included.

2. **Trace and span ids** in the log pattern, from
   `spring-boot-starter-opentelemetry`, so a log line reads
   `[flight-ops-service,<traceId>,<spanId>,<requestId>]`.

3. **Domain counters** that HTTP metrics cannot express, in
   `src/main/java/com/smit/flightops/observability/BookingMetrics.java` and
   `src/main/java/com/smit/flightops/observability/OutboxMetrics.java`.

I configured no OTLP exporter endpoint.

## Consequences

* A 401 carries an id, and a 401 is the response people ring up about. A
  filter ordered after the security chain would have left those without one,
  because Spring Security rejects the request before `DispatcherServlet` is
  reached.

* **Inbound ids are checked.** The id lands in a log line and in a response
  header, so an unchecked value is two injection sinks: `\r\n` forges a second
  log entry or a second header. A malformed diagnostic header is no reason to
  fail someone's booking, so the filter replaces it without an error.

* The id is not in the error body. `ErrorResponse` is a published contract, and
  the id is already on the same response as a header.

* **Opposite defaults.** The starter's two halves default in opposite
  directions, which matters before deploying it. Traces are opt-in: nothing is
  exported until an endpoint is set. Metrics are opt-out:
  `micrometer-registry-otlp` is a *push* registry that starts publishing to
  `localhost:4318` with nothing listening. The first run after I added the
  starter logged those refused connections, and `application.yml` now disables
  OTLP metrics explicitly.

* Structured logging is on for the `prod` profile only
  (`logging.structured.format.console: ecs`). The default profile is for
  someone reading the output by eye.

* Nothing is deployed and nothing scrapes the meters, so they are evidence and
  drive no alerts.
  [OPERATIONS.md](../doc/OPERATIONS.md#what-is-not-wired-up) says so.

## Alternatives considered

* **`logstash-logback-encoder`.** The usual answer, and now redundant. Boot 4
  ships structured logging with MDC inclusion built in, so the dependency would
  add a second way to do the same thing.

* **An OTLP collector.** Adding one to the deployment has real value and real
  cost. In the demo it would answer nothing the log ids do not already answer.

* **Tagging counters by flight.** Tagging the booking counters by flight number
  gives unbounded cardinality, and a Prometheus outage waiting for a busy day.
