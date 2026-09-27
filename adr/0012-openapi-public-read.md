# 12. The OpenAPI document is public; the API it describes is not

Status: accepted (recorded 2026-09-22, decision taken in commit `82ea9b4`; the
annotation scope was widened in `ccad5b4` and `2fb66de`, and recorded in place
on 2026-09-23)

## Context

The API had no machine-readable description. Adding springdoc is easy. The
harder question is authorisation, because `anyRequest().denyAll()` (see
[ADR 0005](0005-one-rule-set-for-basic-and-jwt.md)) means `/v3/api-docs`
answers 401 to an anonymous caller, and 403 to a signed-in one, until someone
decides otherwise.

## Decision

I added `springdoc-openapi-starter-webmvc-ui` 3.1.1. `GET` and `HEAD` on
`/v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html` and
`/swagger-ui/**` are permitted anonymously
(`src/main/java/com/smit/flightops/config/SecurityConfig.java`). Every
operation still requires credentials.

The permit stops at those two verbs. A `POST` to a docs path is not a
documented operation, so it falls to `denyAll()`, and `OpenApiTest` asserts
the 401. The paths are listed one by one. `/v3/**` would have made the next
`/v3/anything` public by accident, and no failing test would catch it.

`src/main/java/com/smit/flightops/config/OpenApiConfig.java` assembles the
document itself: the title, the version from `BuildProperties` when present,
the licence, and one `basicAuth` security scheme applied globally. The
security requirement is declared once, on the document. Per-operation
annotations repeat on every method, and the one someone forgets is the
endpoint documented as public. `bearerAuth` is absent, because the JWT half of
`SecurityConfig` only activates when an issuer is configured. Advertising a
method the running instance rejects is worse than advertising none.
`src/test/java/com/smit/flightops/OpenApiTest.java#theSecurityRequirementIsDocumentedOnceAndAppliesToEverything`
pins both.

To see both halves on a local run:

```bash
curl -s localhost:8080/v3/api-docs | jq '.paths | keys'      # no credentials needed
curl -s -o /dev/null -w '%{http_code}\n' localhost:8080/api/v1/flights   # 401
```

## Consequences

* **Public description, private data.** Reading the description is not the
  same as reading the data. The document lists paths, shapes and error codes.
  Anyone who can reach the service can already find those by probing it.
  Refusing to publish them buys obscurity, and it costs every client a
  hand-written client.

* The Swagger UI stays on in the AWS configuration (`deploy/aws/up.sh` prints
  its URL at the end), and `SWAGGER_UI_ENABLED=false` turns it off while
  keeping the JSON. A client generator reads the JSON, and the console sends
  live requests, so the two carry different risk. On a service with a real
  user base the console would be off in production and on everywhere else.

* **The document is tested.** `OpenApiTest` fetches `/v3/api-docs` anonymously
  and asserts that `/api/v1/flights` is still `401`. It also compares the
  documented page schema against the keys of a real authenticated response, so
  the `{content, page{...}}` shape cannot drift from what is published.

* Every operation carries `@Operation` and `@ApiResponses`, 401 and 403
  included. `OpenApiTest.RESPONSES` lists the documented status codes of each
  one, and
  `src/test/java/com/smit/flightops/OpenApiTest.java#theDocumentCoversTheApiAndItsFailures`
  fails when the document and the list differ. The reads carry a one-line
  summary and no description. A description that restates a getter's name is
  how these documents stop being read. The 503 `DATABASE_UNAVAILABLE` that
  every operation can return, and the `Retry-After` and `X-Request-Id`
  headers, are added once by
  `src/main/java/com/smit/flightops/config/OpenApiConfig.java#sharedResponses`,
  for the reason the security requirement is declared once.

* The springdoc starter pulls in swagger-core, which needs a newer Jackson 2
  than Boot 4.1.1 manages. I override `jackson-2-bom.version` for that reason,
  and the enforcer's `requireUpperBoundDeps` is what caught it. See
  [ADR 0007](0007-spring-boot-4.md).

## Alternatives considered

* **A private document.** Requiring credentials for the document too is
  defensible. It also makes `curl`-and-read impossible for anyone evaluating
  the service, including the intended reader of this repository. A documented
  API that needs a shared password to read about tends to end up documented in
  a wiki.

* **Commit a hand-written OpenAPI document.** It is correct on the day it is
  written. A generated document is correct on every commit, and the test keeps
  the generated output accurate.

* **Annotations on some operations only.** Less to write and less to keep
  true. It also leaves out statuses a client will meet, and a generated client
  then has no type for them.

**Correction (2026-09-23).** This record used to say that only the operations
with interesting failure modes carry `@Operation` and `@ApiResponses`. It
rejected annotating every endpoint as noise, and called the reads
self-describing. The partial set left out statuses the operations return. No
read listed 401 or 403, which come from the security filter chain where
springdoc cannot see them. The flight status change left out the 503 it
returns behind a booking's row lock. A generated client learns its error cases
from this document, so every operation now lists its success status, 401 and
403, and the error codes its own logic returns (`ccad5b4`). The three writes
with a body also list 415, since they read JSON only and refuse a YAML body
(`2fb66de`). Statuses that Spring MVC raises for any endpoint are not listed:
406 for an `Accept` header the API cannot serve, and 500. The summaries stay
one line, so the old concern about noise still shapes the text.
