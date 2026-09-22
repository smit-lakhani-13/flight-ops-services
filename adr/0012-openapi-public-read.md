# 12. The OpenAPI document is public; the API it describes is not

Status: accepted (recorded 2026-09-22, decision taken in commit `82ea9b4`)

## Context

The API had no machine-readable description. Adding springdoc is easy. The
harder question is authorisation, because `anyRequest().denyAll()` (see
[ADR 0005](0005-one-rule-set-for-basic-and-jwt.md)) means `/v3/api-docs` is a
403 until someone decides otherwise.

## Decision

I added `springdoc-openapi-starter-webmvc-ui` 3.1.1. `GET` and `HEAD` on
`/v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html` and
`/swagger-ui/**` are permitted anonymously
(`src/main/java/com/smit/flightops/config/SecurityConfig.java`). Every
operation still requires credentials.

`src/main/java/com/smit/flightops/config/OpenApiConfig.java` assembles the
document itself: the title, the version from `BuildProperties` when present,
the licence, and one `basicAuth` security scheme applied globally.

## Consequences

* **Public description, private data.** Reading the description is not the
  same as reading the data. The document lists paths, shapes and error codes.
  Anyone who can reach the service can already find those by probing it.
  Refusing to publish them buys obscurity, and it costs every client a
  hand-written client.

* The Swagger UI is reachable on the demo deployment, and
  `SWAGGER_UI_ENABLED=false` turns it off. On a service with a real user base
  it would be off in production and on everywhere else.

* **The document is tested.** `OpenApiTest` fetches `/v3/api-docs` anonymously
  and asserts that `/api/v1/flights` is still `401`. It also compares the
  documented page schema against the keys of a real authenticated response, so
  the Boot 4 `{content, page{...}}` shape cannot drift from what is published.

* Only the operations with interesting failure modes carry `@Operation` and
  `@ApiResponses`. Annotating every getter with a description that restates its
  name is how these documents stop being read.

* The springdoc starter pulls in swagger-core, which needs a newer Jackson 2
  than Boot 4.1.1 manages. I override `jackson-2-bom.version` for that reason,
  and the enforcer's `requireUpperBoundDeps` is what caught it. See
  [ADR 0007](0007-spring-boot-4.md).

## Alternatives considered

* **A private document.** Requiring credentials for the document too is
  defensible. It also makes `curl`-and-read impossible for anyone evaluating
  the service, including the intended reader of this repository.

* **Commit a hand-written OpenAPI document.** It is correct on the day it is
  written. A generated document is correct on every commit, and the test keeps
  the generated output accurate.

* **`@Operation` on every endpoint.** Noise. Three of the five write operations
  carry the error codes: creating and cancelling a booking, and changing a
  flight's status. The reads are self-describing.
