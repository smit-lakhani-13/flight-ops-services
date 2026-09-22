# 12. The OpenAPI document is public; the API it describes is not

Status: accepted (recorded 2026-09-22, decision taken in commit `82ea9b4`)

## Context

The API had no machine-readable description. Adding springdoc is easy; the
interesting question is authorisation, because `anyRequest().denyAll()` (see
[ADR 0005](0005-one-rule-set-for-basic-and-jwt.md)) means `/v3/api-docs` is a
403 until somebody decides otherwise.

## Decision

`springdoc-openapi-starter-webmvc-ui` 3.1.1, with `GET` and `HEAD` on
`/v3/api-docs`, `/v3/api-docs/**`, `/v3/api-docs.yaml`, `/swagger-ui.html` and
`/swagger-ui/**` permitted anonymously
(`src/main/java/com/smit/flightops/config/SecurityConfig.java`), and every
operation still requiring credentials.

The document itself is assembled in
`src/main/java/com/smit/flightops/config/OpenApiConfig.java`: title, version
from `BuildProperties` when present, licence, and one `basicAuth` security
scheme applied globally.

## Consequences

* **Reading the description is not the same as reading the data.** The document
  lists paths, shapes and error codes. Anyone who can reach the service can
  already discover those by probing it; refusing to publish them buys obscurity
  and costs every client a hand-written client.
* The Swagger UI is reachable on the demo deployment, switchable with
  `SWAGGER_UI_ENABLED`. On a service with a real user base it would be off in
  production and on everywhere else.
* The document is verified, not assumed. `OpenApiTest` fetches
  `/v3/api-docs` anonymously, asserts `/api/v1/flights` is still `401`, and —
  the part that matters — compares the documented page schema against the keys
  of a real authenticated response, so the Boot 4 `{content, page{...}}` shape
  cannot drift from what is published.
* Only the operations with interesting failure modes carry `@Operation` and
  `@ApiResponses`. Annotating every getter with a description that restates its
  name is how these documents stop being read.
* springdoc pulls swagger-core, which needs a newer Jackson 2 than Boot 4.1.1
  manages; `jackson-2-bom.version` is overridden for that reason and the
  enforcer's `requireUpperBoundDeps` is what caught it. See
  [ADR 0007](0007-spring-boot-4.md).

## Alternatives considered

* **Require credentials for the document too.** Defensible, and it makes
  `curl`-and-read impossible for anyone evaluating the service, including the
  intended reader of this repository.
* **Commit a hand-written OpenAPI document.** It is correct on the day it is
  written. A generated document is correct on every commit, and the test is
  what keeps the generation honest.
* **`@Operation` on every endpoint.** Noise. The three write paths carry the
  error codes; the reads are self-describing.
