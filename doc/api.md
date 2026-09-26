# API

The HTTP contract of the service: who may call what, what each call returns,
and what every error code means.

The examples assume the app is running on `localhost:8080` in the default
profile, started as [the README](../README.md#run-it) shows. Every profile but
`prod` seeds three flights: `UA123` from EWR to LHR with 180 seats, `UA456`
from ORD to SFO with 150, and `UA789` from EWR to SFO with 200.

## Contents

- [Authentication](#authentication)
- [Operations](#operations)
- [Request rules](#request-rules)
- [Error envelope](#error-envelope)
- [Error codes](#error-codes)
- [Worked examples](#worked-examples)
- [Paging and sorting](#paging-and-sorting)
- [OpenAPI](#openapi)

## Authentication

Every call under `/api/` needs credentials. The default profile ships two
throwaway accounts:

| Account | Password | Authorities | Used for |
|---|---|---|---|
| `api` | `dev-secret` | `SCOPE_flights:read`, `SCOPE_flights:write` | every operation under `/api/v1/` |
| `ops` | `dev-ops` | `ROLE_OPS` | the actuator endpoints other than health |

In `application.yml` their defaults are `{noop}dev-secret` and
`{noop}dev-ops`. The prefix tells the `DelegatingPasswordEncoder` which
algorithm to use, and `{noop}` means the value is not hashed, so it marks a
value as not secret. A deployment would pass `{bcrypt}` hashes in
`API_PASSWORD` and `OPS_PASSWORD`.

The `prod` profile has no defaults, and two startup checks stand between a bad
value and a login:

- `ApiSecurityProperties` rejects any value without an `{id}` prefix. A
  deployment that forgets `API_PASSWORD` therefore fails at startup, and the
  message names `app.security.api-password (API_PASSWORD)`.
- `SecurityConfig` then asks the encoder to verify each value once
  (`config/SecurityConfig.java#assertVerifiable`). An id the encoder does not
  know, such as `{foo}`, also stops startup, and so does `{argon2}`, because
  the build has no BouncyCastle.

Neither check prints the value. Without them, `@ConfigurationProperties` would
bind the literal string `${API_PASSWORD}` as the password, and the service
would start and then fail every login as that user with a 500.
[SECURITY.md](../SECURITY.md#secrets) has the detail of both checks, and the
malformed value they let through.

### Basic or bearer

Callers use HTTP Basic or a bearer token. Bearer tokens are validated as an
OAuth2 resource server once
`spring.security.oauth2.resourceserver.jwt.issuer-uri` is set. With no issuer
there is no `JwtDecoder` bean, and the service starts with Basic alone. A
token's `scope` claim maps to the same `SCOPE_` strings the `api` user holds,
so the rules do not know which mechanism authenticated a request. Set
`audiences` with the issuer;
[SECURITY.md](../SECURITY.md#authentication-and-authorisation) says why, and
also holds the rule for each path, the `/error` rule and the reason the last
rule is `denyAll()`.

### 401 and 403

A 401 means the caller is unknown. A 403 means it is known and not allowed.

```bash
curl -s localhost:8080/api/v1/flights/UA123              # no credentials
# 401, WWW-Authenticate: Basic realm="flight-ops-service"
# {"code":"UNAUTHENTICATED","message":"Authentication is required to access this resource","timestamp":"2026-09-25T08:46:53.522905Z"}

curl -s -u api:dev-secret localhost:8080/actuator/metrics  # api on an ops endpoint
# 403
# {"code":"FORBIDDEN","message":"Your credentials do not grant access to this resource","timestamp":"2026-09-25T08:47:03.007894Z"}
```

A wrong password gets the same 401 and the same message, so the body never says
whether the user exists. Once the resource server is on, a rejected bearer
token gets `Bearer realm="flight-ops-service", error="invalid_token"` as its
challenge. With no issuer set, a bearer token is ignored and the request gets
the `Basic` 401 above. [SECURITY.md](../SECURITY.md#401-and-403) explains why
the two statuses stay apart.

## Operations

| Method | Path | Requires | Success | Failures |
|---|---|---|---|---|
| `GET` | `/api/v1/flights/{flightNumber}` | `flights:read` | 200 | 404 |
| `GET` | `/api/v1/flights?origin=&destination=&page=&size=&sort=` | `flights:read` | 200 (paginated) | 400 |
| `POST` | `/api/v1/flights` | `flights:write` | 201 + `Location` | 400, 409, 415 |
| `PATCH` | `/api/v1/flights/{flightNumber}/status` | `flights:write` | 200 | 400, 404, 409, 415, 503 |
| `DELETE` | `/api/v1/flights/{flightNumber}` | `flights:write` | 204 | 404, 409, 503 |
| `POST` | `/api/v1/bookings` | `flights:write` | 201 + `Location` | 400, 404, 409, 415, 503 |
| `GET` | `/api/v1/bookings/{bookingId}` | `flights:read` | 200 | 400, 404 |
| `GET` | `/api/v1/bookings?flightNumber=&page=&size=&sort=` | `flights:read` | 200 (paginated) | 400 |
| `DELETE` | `/api/v1/bookings/{bookingId}` | `flights:write` | 200 | 400, 404, 503 |
| `GET` | `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | none | 200 | 401 on a wrong password; 503 while the status is `DOWN` or `OUT_OF_SERVICE`, so a database outage takes `/health` to 503 and leaves liveness and readiness at 200 |
| `GET` | `/actuator`, `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus` | `ROLE_OPS` | 200 | 401, 403 |

Every `/api/**` row also answers 401 without valid credentials, a wrong
password included, and 403 to an authenticated caller who lacks the authority.
`HEAD` follows the same rule as `GET`. The health rows need no credentials,
and only `ops` sees the components behind the status.

A few behaviours the table does not show:

- `POST /api/v1/flights` trims and upper-cases the flight number, and the
  `Location` header names that form, so `ua999` is created at
  `/api/v1/flights/UA999`.
- A replayed booking answers 201 with the booking the key first created. See
  [Retries](#retries).
- Both `DELETE`s are safe to repeat. A cancelled flight answers 204 again, and
  a cancelled booking answers 200 with its original `cancelledAt`. Nothing is
  deleted: a cancelled row keeps its history.

Not every 503 has the same test behind it. A booking and a booking
cancellation wait out a real row lock in `LockTimeoutTest`, and the booking
does on PostgreSQL in CI too (`LockTimeoutPostgresTest`). The 503s for a
status change, a flight cancellation and `DATABASE_UNAVAILABLE` are checked
only in the `@WebMvcTest` slice, with the service mocked
(`FlightControllerTest`). No test takes the database down to watch
`/actuator/health` answer 503.

## Request rules

Both controllers produce and read JSON only.

- An `Accept` header of `application/xml` gets
  `406 REQUEST_REJECTED`, written as JSON.
- A `POST` or `PATCH` whose `Content-Type` is missing or is not
  `application/json` gets `415 UNSUPPORTED_MEDIA_TYPE`, YAML and multipart
  included, and the response's `Accept` header names JSON. So `curl -d` needs
  `-H 'Content-Type: application/json'`. A `DELETE` reads no body, so its
  `Content-Type` is not checked. Multipart parsing is off
  (`spring.servlet.multipart.enabled: false`), because the API takes no
  uploads.
- The header decides, so a YAML body sent as `application/json` is
  `400 MALFORMED_REQUEST`.
- Any `/api/**` row can also answer `503 DATABASE_UNAVAILABLE`, with
  `Retry-After`, when the service cannot reach its database.

Tomcat refuses some requests before Spring sees them: `%2F`, `%5C`, `%00` or
`%zz` in the path, a raw `|`, or a 20KB header. Those get Tomcat's own HTML
400 page with no `X-Request-Id`. A path that Spring Security's firewall
refuses, such as one with `;` or `//` in it, gets `400 BAD_REQUEST` in the
usual JSON envelope instead, from `ApiErrorController`. An unknown path under
an exposed actuator endpoint, such as `/actuator/metrics/nope` asked for as
`ops`, returns an empty 404.

Jackson and Hibernate exception text names internal classes, tables and
columns, so the client gets a fixed string and the detail goes to the log at
WARN. The project's own exception messages are written for clients, and those
pass through: `FlightNotFoundException`, `BookingNotFoundException`,
`InsufficientSeatsException`, `FlightNotBookableException`,
`DuplicateFlightException`, `IllegalFlightTransitionException`,
`IdempotencyKeyConflictException` and `UnknownSortPropertyException`. So does
Spring MVC's own `ErrorResponse` detail, such as
`Method 'POST' is not supported.`, which names only the request.

Two cases get a fixed message instead:

- A `POST` or `PATCH` with no `Content-Type`. Spring would say
  `Content-Type 'null' is not supported.`, so it gets
  `The request has no Content-Type. Send application/json.`
- A stray `IllegalArgumentException` gets
  `The request contained an invalid value.`

The fixed strings for the other codes are in
`exception/GlobalExceptionHandler.java`: `CONCURRENT_MODIFICATION`,
`DUPLICATE_REQUEST`, `LOCK_TIMEOUT`, `DATABASE_UNAVAILABLE`, the
`MALFORMED_REQUEST` for a body Jackson cannot read, and `INTERNAL_ERROR`.
`ApiErrorController` answers with one of two fixed messages, because the
container's own error text can name an internal path or exception class.

## Error envelope

Every error the three classes below write has one of two JSON shapes.
Tomcat's HTML 400 page and the actuator's empty 404, described under
[Request rules](#request-rules), come from outside them. Most errors are
`{code, message, timestamp}`:

```json
{"code":"FLIGHT_NOT_BOOKABLE","message":"Flight UA456 is CANCELLED and cannot be booked","timestamp":"2026-09-25T08:47:15.944284Z"}
```

A Bean Validation failure is `{code, fieldErrors, timestamp}`, with one
message per field, so a client can attach each message to its input:

```json
{"code":"VALIDATION_FAILED","fieldErrors":{"passengerName":"must not be blank","seats":"must be greater than or equal to 1"},"timestamp":"2026-09-25T08:53:36.978874Z"}
```

Three classes write them:

| Writer | What it answers |
|---|---|
| `exception/GlobalExceptionHandler.java` | every exception a controller lets through, and Spring MVC's own 404, 405, 406 and 415 |
| `security/ErrorResponseWriter.java` | the 401 and 403, for `JsonAuthenticationEntryPoint` and `JsonAccessDeniedHandler`. Spring Security decides those before the `DispatcherServlet` runs, so the handler above never sees them |
| `exception/ApiErrorController.java` | `/error`, where the container forwards a failure raised outside Spring MVC, such as a path the firewall refuses or a `TRACE` |

Each sets `Content-Type: application/json` itself, whatever the `Accept`
header asked for. No controller contains a `try`/`catch`. All three take the
timestamp from the one injected `Clock`. `RequestIdFilter` returns
`X-Request-Id` on every response the application handles, 401 and 403
included.

## Error codes

| Code | Status | Meaning |
|---|---|---|
| `FLIGHT_NOT_FOUND` | 404 | no such flight number |
| `BOOKING_NOT_FOUND` | 404 | no such booking id; its own code, so a booking 404 does not claim the flight is missing |
| `INSUFFICIENT_SEATS` | 409 | fewer seats remain than requested; a retry with fewer seats can succeed |
| `FLIGHT_NOT_BOOKABLE` | 409 | the flight is `CANCELLED`, `DEPARTED` or `ARRIVED`; a retry can never succeed |
| `DUPLICATE_FLIGHT` | 409 | the flight number already exists |
| `CONCURRENT_MODIFICATION` | 409 | `@Version` rejected a stale write |
| `DUPLICATE_REQUEST` | 409 | two flight-creation requests raced on `flight_number` and the constraint chose one; a raced booking recovers instead |
| `IDEMPOTENCY_KEY_REUSED` | 409 | the key was first used for a different request. That is a client bug; a true replay returns the original booking |
| `ILLEGAL_STATUS_TRANSITION` | 409 | the flight cannot go from its status to the requested one (`BOARDING → ARRIVED`, anything out of `ARRIVED` or `CANCELLED`, or a `DELETE` on a flight that has departed or arrived) |
| `LOCK_TIMEOUT` | 503 + `Retry-After: 1` | a write waited out the 3s `lock_timeout` on the flight row: a booking or a booking cancellation on its `SELECT … FOR UPDATE`, or a status change or flight cancellation queued behind one. The request was valid and the row was busy, so retry after `Retry-After` |
| `DATABASE_UNAVAILABLE` | 503 + `Retry-After: 1` | no database connection: the pool stayed empty for its whole connection timeout, or the database did not answer. The request was valid and can succeed later, so it is a 503 and not a 500, and it is logged at WARN with no stack trace |
| `UNKNOWN_SORT_PROPERTY` | 400 | the sort property is not on the endpoint's published list; see [Paging and sorting](#paging-and-sorting) |
| `UNAUTHENTICATED` | 401 | no credentials, or credentials that do not verify; written by `JsonAuthenticationEntryPoint` |
| `FORBIDDEN` | 403 | authenticated, without the authority this path needs; written by `JsonAccessDeniedHandler` |
| `VALIDATION_FAILED` | 400 | Bean Validation, per field, including `@DistinctEndpoints`, which refuses a flight from EWR to EWR. A field that breaks more than one rule gets one message, taken in this order: null, blank, size or range, pattern, any other rule. So an empty airport code gets `must not be blank`, not `size must be between 3 and 3`. A flight number with a space, `/` or `%` inside gets `must contain only letters and digits`. An airport code with a digit, symbol or padding gets `must contain only letters`. A passenger name with a control character gets `must not contain control characters`, one with an unpaired UTF-16 surrogate gets `must not contain unpaired surrogates`, and one made only of spaces, no-break spaces or format characters such as U+200B and U+FEFF gets `must not be blank`; for the last two that message comes from a pattern, so such a name past 255 characters gets the size message instead. An idempotency key with a character other than letters, digits and `. _ : -` gets `must contain only letters, digits and . _ : -`. A missing or null `departureTime` gets `must not be null` |
| `MALFORMED_REQUEST` | 400 | unreadable body, an unknown enum constant or one sent as a number, a `seats` or `totalSeats` that is missing, null, quoted, or written with a decimal point or an exponent (`2.0` included), a `departureTime` that is not an ISO-8601 instant with `Z` or an offset (a missing or null one is `VALIDATION_FAILED`), bad path variable, missing query parameter, or `page * size` above 2147483647 on either list endpoint |
| `RESOURCE_NOT_FOUND` | 404 | unmapped path |
| `METHOD_NOT_ALLOWED` | 405 | a verb the security rules allow on a path that does not map it, such as `POST` on `/api/v1/flights/UA123`; the `Allow` header lists the mapped verbs. Tomcat refuses `TRACE` before any filter runs, so its 405 comes from `ApiErrorController`, with the servlet's full `Allow` list and no `X-Request-Id`. `PUT` and `OPTIONS` get 403 from `anyRequest().denyAll()`, or 401 without credentials |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | a `Content-Type` that is missing or is not `application/json`, YAML included; the `Accept` header names JSON |
| `REQUEST_REJECTED` | 4xx | any other Spring MVC client error, such as the 406 for a non-JSON `Accept` |
| `BAD_REQUEST` | 4xx | any other client error the container forwards to `/error`, such as a path the firewall refuses; written by `ApiErrorController` in the same envelope |
| `INTERNAL_ERROR` | 500 | last resort; the stack trace is logged and never returned |

## Worked examples

### Retries

A retry does not double-book. On a freshly started app:

```bash
curl -s -u api:dev-secret -X POST localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA123","passengerName":"Test Passenger","seats":3,"idempotencyKey":"demo-1"}'
curl -s -u api:dev-secret localhost:8080/api/v1/flights/UA123          # availableSeats: 177

for i in 1 2 3 4 5; do                                    # the client retried after a timeout
  curl -s -u api:dev-secret -X POST localhost:8080/api/v1/bookings \
    -H 'Content-Type: application/json' \
    -d '{"flightNumber":"UA123","passengerName":"Test Passenger","seats":3,"idempotencyKey":"demo-1"}' >/dev/null
done

curl -s -u api:dev-secret localhost:8080/api/v1/flights/UA123          # STILL 177, not 162
curl -s -u api:dev-secret "localhost:8080/api/v1/bookings?flightNumber=UA123"   # ONE booking
```

Every line spells out `-u`, because the obvious tidy-up,
`A='-u api:dev-secret'; curl $A ...`, fails in zsh, the default shell on
macOS. zsh does not word-split an unquoted parameter, so curl receives
`-u api:dev-secret` as one argument, reads the user name as ` api` with a
leading space, and every call returns 401. Use an array instead:
`A=(-u api:dev-secret); curl "${A[@]}" ...`

Change the payload and keep the key, and the answer is
`409 IDEMPOTENCY_KEY_REUSED`:

```bash
curl -s -u api:dev-secret -X POST localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA123","passengerName":"Test Passenger","seats":4,"idempotencyKey":"demo-1"}'
# 409 {"code":"IDEMPOTENCY_KEY_REUSED","message":"Idempotency key demo-1 has already been used for a different booking. Use a new key, or resend the original request unchanged.", ...}
```

A retry is the same request arriving twice. A different request on the same key
is a client bug, and returning someone else's booking would hide it. "The same
request" means the same flight number, passenger name and seat count: the
service compares a SHA-256 fingerprint of those three fields with the one
stored against the key (`dto/BookingRequest.java#fingerprint`). The flight
number is compared without case and the name without its padding. The
decision table is in
[ARCHITECTURE.md](ARCHITECTURE.md#idempotency-as-a-decision-table).

### A cancelled flight

A cancelled flight refuses bookings:

```bash
curl -s -u api:dev-secret -X DELETE localhost:8080/api/v1/flights/UA456   # 204 -> CANCELLED

curl -s -u api:dev-secret -X POST localhost:8080/api/v1/bookings \
  -H 'Content-Type: application/json' \
  -d '{"flightNumber":"UA456","passengerName":"Test Passenger","seats":1,"idempotencyKey":"demo-2"}'
# 409 {"code":"FLIGHT_NOT_BOOKABLE","message":"Flight UA456 is CANCELLED and cannot be booked"}

curl -s -u api:dev-secret localhost:8080/api/v1/flights/UA456           # availableSeats unchanged
```

`CANCELLED` is terminal, and act 6 of [the demo](#the-demo-script) moves
`UA456` to `BOARDING`, so restart the app after this example and before the
demo.

### The status state machine

`PATCH /api/v1/flights/{n}/status` with body `{"status":"DEPARTED"}` moves a
flight through the state machine (`entity/FlightStatus.java#canTransitionTo`).
`SCHEDULED`, `BOARDING` and `DELAYED` sell seats. A booking on a `DEPARTED`,
`ARRIVED` or `CANCELLED` flight gets `409 FLIGHT_NOT_BOOKABLE`
(`entity/FlightStatus.java#isBookable`). `BOARDING → ARRIVED` is
`409 ILLEGAL_STATUS_TRANSITION`, because an aircraft cannot land without
departing, and `CANCELLED` and `ARRIVED` are terminal. Moving a flight to the
status it already has is allowed, so a retried `PATCH` is safe. The diagram is
in [ARCHITECTURE.md](ARCHITECTURE.md#flight-status-is-a-state-machine). The
cancelled-flight refusal was added after a probe of a running instance found
it missing ([the defect log](DEFECT-LOG.md#seats-sold-on-a-cancelled-flight)).

### The demo script

With the app running, in a second terminal:

```bash
scripts/demo.sh          # pauses between acts, so you can talk over it
scripts/demo.sh --fast   # no pauses
```

Eight acts run over HTTP:

1. The paged flight search, and a flight created in lower case whose
   `Location` header names the normalised number.
2. Idempotent replay: a booking, its `Location` followed, and a replay that
   returns the same booking and leaves the seat count alone.
3. The error codes: per-field validation, `INSUFFICIENT_SEATS`, and
   `FLIGHT_NOT_BOOKABLE` on a flight the act has just cancelled.
4. Ten callers race on one key with one payload. All ten get the same
   booking, and one seat is sold.
5. Ten callers race on one key with ten payloads. One gets 201 and nine are
   told the key is taken.
6. `SCHEDULED → BOARDING` accepted, then `BOARDING → ARRIVED` refused.
7. The 401 and 403 split: no credentials, `ops` on the API, and `api` on the
   metrics.
8. The health endpoints a Kubernetes probe would call, with no credentials,
   and `/actuator/prometheus`, which needs `ops`.

The script needs bash, curl and python3. It pretty-prints JSON with `jq` when
`jq` is installed and with `python3 -m json.tool` when it is not, and acts 4
and 5 call python3 either way. Before the first act it checks that the app
answers and that the credentials work. `AUTH` and `OPS_AUTH` override the
credentials, as in `AUTH='-u someone:something' scripts/demo.sh`. The flights
a run creates carry a suffix taken from the clock, so a second run does not
collide with the first. Restart the app to reset its in-memory state.

## Paging and sorting

Both list endpoints, `GET /api/v1/flights` and `GET /api/v1/bookings`, take
Spring Data's three parameters:

| Parameter | Meaning | Default |
|---|---|---|
| `page` | zero-based page number | `0` |
| `size` | rows per page, at most 100 | `20` |
| `sort` | `property`, `property,asc` or `property,desc`, optionally followed by `,ignorecase`; repeat the parameter to sort by more than one property | flights by `departureTime`, bookings by `createdAt`, both ascending |

The defaults come from `@PageableDefault` on
`controller/FlightController.java#search` and
`controller/BookingController.java#byFlight`. The cap is
`spring.data.web.pageable.max-page-size: 100` in
`src/main/resources/application.yml#max-page-size`. A larger `size` is clamped,
not rejected: `?size=5000` answers 200 and asks the service for 100 rows
(`controller/BookingControllerTest.java#pageSizeIsCappedAtOneHundred`). A
`size` below 1 falls back to 20 and a negative or unreadable `page` to 0. That
is Spring Data's own handling, observed over HTTP and not pinned by a test
here.

The response is `{content, page}`, with `page` holding `size`, `number`,
`totalElements` and `totalPages` (`serialization-mode: via-dto` in
`application.yml`).

The filters: `origin` and `destination` are both optional on the flight
search, and each is trimmed and upper-cased before the query. `flightNumber`
is required on the bookings list, is trimmed and upper-cased too, and an
unknown flight number is an empty page.

`controller/SortPolicy.java#stable` applies the same rules to both endpoints.
Each endpoint publishes the properties it sorts by:

| Endpoint | Sortable properties | `ignorecase` applies to |
|---|---|---|
| `GET /api/v1/flights` | `id`, `flightNumber`, `origin`, `destination`, `totalSeats`, `availableSeats`, `status`, `departureTime` | `flightNumber`, `origin`, `destination` |
| `GET /api/v1/bookings` | `id`, `createdAt`, `passengerName`, `seats`, `cancelledAt` | `passengerName` |

The lists are `controller/FlightController.java#SORTABLE` and
`controller/BookingController.java#SORTABLE`, each with a `TEXTUAL` subset.
They are checked against what the endpoint offers, not against the entity.
`version` is left out on flights, because ordering by it shows how often a row
was written. `idempotencyKey` is left out on bookings, because sorting by it
would give other clients' keys back a comparison at a time.

- **An unknown property** is `400 UNKNOWN_SORT_PROPERTY`, with the message
  `'deptime' is not a sortable property.` The message echoes the name the
  caller sent and never lists the real properties. A property the entity has
  and the endpoint does not offer, such as `idempotencyKey`, gets the same 400
  (`ErrorContractTest.java#idempotencyKeyIsNotSortable`).
- **`ignorecase`** is kept only on the properties in the last column. On any
  other property it is dropped and the sort runs as if it had not been sent.
  The bookings list is a declared `@Query`, which wraps an ignore-case column
  in `lower()` whatever its type, and Hibernate refuses that for a number or a
  time: `?sort=seats,asc,ignorecase` was a 500
  (`ErrorContractTest.java#ignoreCaseOnANonTextPropertyIsDropped`).
- **A tiebreaker.** `id` ascending is appended unless the caller already
  sorted by `id`, because a sort on a column that is not unique lets page 0
  and page 1 overlap or skip rows.
- **Overflow.** When `page * size`, after the size is clamped, is larger than
  2147483647, the answer is `400 MALFORMED_REQUEST` with the message
  `page * size must not exceed 2147483647.` Spring Data computes the row
  offset as an `int`, and the query would otherwise fail with a 500. At the
  default size of 20, page 107374182 is the last one that fits
  (`ErrorContractTest.java#pagePastTheLastAddressableRowIsABadRequest`).

How the unstable sort and the 500 for an unknown property were found is in
[the defect log](DEFECT-LOG.md#paging-that-could-skip-a-row). The overflow
has [its own entry](DEFECT-LOG.md#a-page-past-the-last-row-was-a-500).

## OpenAPI

| Path | What it serves |
|---|---|
| `/v3/api-docs` | the OpenAPI document, as JSON |
| `/v3/api-docs.yaml` | the same document, as YAML |
| `/swagger-ui.html` | Swagger UI, by a redirect to `/swagger-ui/index.html` |

`GET` and `HEAD` on these paths and on `/swagger-ui/**` need no credentials,
and every operation the document lists still does, for the reasons in
[ADR 0012](../adr/0012-openapi-public-read.md). Any other method on them falls
to `denyAll()`, so a `POST` to `/v3/api-docs` without credentials is a 401.
The document declares one `basicAuth` scheme and applies it to every
operation. It declares no bearer scheme, because the JWT half of
`SecurityConfig` activates only when an issuer is configured.

`SWAGGER_UI_ENABLED=false` sets `springdoc.swagger-ui.enabled` in
`application.yml` and turns Swagger UI off. `/swagger-ui.html` then answers
`404 RESOURCE_NOT_FOUND`, and the JSON and YAML documents are still served.
Nothing in `application.yml` turns the documents off:
`springdoc.api-docs.enabled` is fixed at `true`. Swagger UI opens with "Try
it out" enabled (`try-it-out-enabled: true`), so the page sends live requests
with whatever credentials are entered in it.
