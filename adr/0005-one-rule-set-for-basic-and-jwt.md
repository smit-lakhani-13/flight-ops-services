# 5. One authorisation rule set for Basic and JWT

Status: accepted (recorded 2026-09-22, decision taken in commit `e83d846`)

## Context

Two kinds of caller need to reach the API: people (and scripts) holding
credentials, and machines holding a token from an identity provider. The
obvious implementation is two security filter chains with two sets of path
rules, one for Basic and one for JWT.

Two sets of rules means two places to edit when a new endpoint is added, and
the second one is the one that gets forgotten. Nothing reports that failure,
and it fails *open*.

## Decision

One `SecurityFilterChain`
(`src/main/java/com/smit/flightops/config/SecurityConfig.java#apiSecurityFilterChain`)
with one set of `authorizeHttpRequests` rules, and two authentication
mechanisms feeding it.

I named the Basic user's authorities to match what the JWT side produces.
Spring's default `JwtGrantedAuthoritiesConverter` maps a token's `scope` claim
to authorities prefixed `SCOPE_`. A token carrying
`scope: "flights:read flights:write"` therefore arrives holding the same
authority strings the Basic user `api` is granted. The rules never learn which
mechanism authenticated the request.

JWT support is registered only when Boot creates a `JwtDecoder` bean. It does
that when `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `jwk-set-uri`
or `public-key-location` is set. With none of them, the service boots with
Basic alone and no dead configuration.

## Consequences

* Adding an endpoint means editing one list. A wrong rule is wrong for every
  caller at once. I want that failure mode, because it is visible.

* The in-memory user store is a stub, and the documentation says so. The
  *rules* are real, and `SecurityRulesTest` tests them against the real filter
  chain. Moving to an identity provider is a configuration change with no
  rewrite.

* Scope naming is now a contract with the future identity provider. It must
  issue `flights:read` and `flights:write`, or the mapping breaks without any
  error.

* **The audience is a second contract.** `issuer-uri` alone accepts any token
  the issuer signed, including one minted for another client in the tenant.
  Turning JWT on also needs
  `spring.security.oauth2.resourceserver.jwt.audiences: [flight-ops-service]`.
  [SECURITY.md](../SECURITY.md) has the warning.

* `anyRequest().denyAll()` closes the list. A new endpoint under `/api/**` is
  covered by the scope rules for GET, HEAD, POST, PATCH and DELETE. One outside
  it, or a method the rules do not name such as `PUT`, is unreachable until
  someone decides who may reach it. That default is why the OpenAPI paths
  needed an explicit rule (see [ADR 0012](0012-openapi-public-read.md)).

## Alternatives considered

* **Two filter chains with `securityMatcher`.** Idiomatic, and it duplicates
  the rules. Every future endpoint has to be added twice.

* **Method security (`@PreAuthorize`).** Moving the rules off the paths and
  next to the code they protect reads well. It also scatters the answer to
  "what can an unauthenticated caller reach?" across every controller method
  instead of one list.

* **A gateway doing authorisation.** Correct in a mesh. This service must still
  be safe when called directly.
