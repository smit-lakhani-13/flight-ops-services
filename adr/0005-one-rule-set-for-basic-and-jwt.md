# 5. One authorisation rule set for Basic and JWT

Status: accepted (recorded 2026-09-22, decision taken in commit `e83d846`)

## Context

Two kinds of caller need to reach the API: people (and scripts) holding
credentials, and machines holding a token from an identity provider. The
obvious implementation is two security filter chains with two sets of path
rules — one for Basic, one for JWT.

Two sets of rules is two places to edit when a new endpoint is added, and the
second one is the one that gets forgotten. The failure is silent and it fails
*open*.

## Decision

One `SecurityFilterChain`
(`src/main/java/com/smit/flightops/config/SecurityConfig.java#apiSecurityFilterChain`) with
one set of `authorizeHttpRequests` rules, and two authentication mechanisms
feeding it.

The two meet on purpose. Spring's default `JwtGrantedAuthoritiesConverter` maps
a token's `scope` claim to authorities prefixed `SCOPE_`, so a token carrying
`scope: "flights:read flights:write"` arrives holding exactly the authority
strings the Basic user `api` is granted. **The rules never learn which
mechanism authenticated the request.**

JWT support is registered conditionally on a `JwtDecoder` bean existing, which
it does only when `spring.security.oauth2.resourceserver.jwt.issuer-uri` is
set. With no issuer configured the service boots with Basic alone and no dead
configuration.

## Consequences

* Adding an endpoint means editing one list. A rule that is wrong is wrong for
  every caller at once, which is the failure mode you want: visible.
* The in-memory user store is a stub and is documented as one. The *rules* are
  real, tested against the real filter chain by `SecurityRulesTest`, and the
  swap to an identity provider is configuration rather than a rewrite.
* Scope naming is now a contract with the future identity provider: it must
  issue `flights:read` and `flights:write`, or the mapping breaks quietly.
* `anyRequest().denyAll()` closes the list, so a new endpoint is unreachable
  until someone decides who may reach it. That default is what made the
  OpenAPI paths need an explicit rule — see
  [ADR 0012](0012-openapi-public-read.md).

## Alternatives considered

* **Two filter chains with `securityMatcher`.** Idiomatic, and it duplicates
  the rules. Every future endpoint has to be added twice.
* **Method security (`@PreAuthorize`) instead of path rules.** Moves the rules
  next to the code they protect, which reads well, and scatters the answer to
  "what can an unauthenticated caller reach?" across thirty files.
* **A gateway doing authorisation.** Correct in a mesh; this service must still
  be safe when called directly.
