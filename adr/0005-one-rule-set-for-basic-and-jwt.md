# 5. One authorisation rule set for Basic and JWT

Status: accepted (recorded 2026-09-22, decision taken in commit [`e83d846`])

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
  chain. Moving to an identity provider whose tokens carry `aud` and the
  scopes `flights:read` and `flights:write` is a configuration change with no
  rewrite. Cognito is not one: it prefixes each custom scope with its resource
  server's identifier, and its client-credentials tokens carry no `aud`, so it
  also needs a scope converter and an audience check on `client_id`. Neither
  is built. [SECURITY.md](../SECURITY.md) has the detail.

* Scope naming is now a contract with the future identity provider. It must
  issue `flights:read` and `flights:write`, or the mapping breaks without any
  error.

* **The audience is a second contract.** `issuer-uri` alone would accept any
  token the issuer signed, including one minted for another client in the
  tenant. Turning JWT on also needs
  `spring.security.oauth2.resourceserver.jwt.audiences: [flight-ops-service]`.
  `config/SecurityConfig.java#requireIssuerAndAudience` now stops startup when a
  decoder property is set without it, or a `jwk-set-uri` without `issuer-uri`.
  [SECURITY.md](../SECURITY.md) has the warning.

* `anyRequest().denyAll()` closes the list. A new endpoint under `/api/**` is
  covered by the scope rules for GET, HEAD, POST, PATCH and DELETE. One outside
  it, or a method the rules do not name such as `PUT`, is unreachable until
  someone decides who may reach it. That default is why the OpenAPI paths
  needed an explicit rule (see [ADR 0012](0012-openapi-public-read.md)). An
  actuator endpoint is the exception: once it is added to
  `management.endpoints.web.exposure.include`, the
  `EndpointRequest.toAnyEndpoint()` rule lets the `ops` account reach it with
  no new rule.

**Correction (2026-09-27).** The audience bullet used to say that `issuer-uri`
alone accepts any token the issuer signed, and left the warning to SECURITY.md.
`config/SecurityConfig.java#requireIssuerAndAudience` now stops startup on that
configuration, so the bullet says what it would accept and names the check.

**Correction (2026-09-29).** The in-memory user store bullet used to say that
moving to any identity provider is a configuration change with no rewrite.
That holds only for an issuer whose tokens carry `aud` and the two scopes as
they are named here. Cognito needs code as well, so the bullet now says which
issuers it covers and what Cognito would need.

## Alternatives considered

* **Two filter chains with `securityMatcher`.** Idiomatic, and it duplicates
  the rules. Every future endpoint has to be added twice.

* **Method security (`@PreAuthorize`).** Moving the rules off the paths and
  next to the code they protect reads well. It also scatters the answer to
  "what can an unauthenticated caller reach?" across every controller method
  instead of one list.

* **A gateway doing authorisation.** Correct in a mesh. This service must still
  be safe when called directly.

[`e83d846`]: https://github.com/smit-lakhani-13/flight-ops-services/commit/e83d846
