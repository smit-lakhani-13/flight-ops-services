# 6. Stateless sessions, and CSRF deliberately off

Status: accepted (recorded 2026-09-22, decision taken in commit `e83d846`)

## Context

Spring Security's defaults are built for a browser application with a session
cookie: it creates an `HttpSession` when it needs one and enables CSRF
protection. This is a JSON API consumed by scripts and services, with
credentials or a bearer token on every request.

Leaving the defaults in place would be the safe-looking choice and the wrong
one. Disabling CSRF is the kind of line that gets flagged in review, so the
reasoning has to be written down rather than argued once in a comment.

## Decision

`SessionCreationPolicy.STATELESS` and `csrf.disable()`, together, in
`src/main/java/com/smit/flightops/config/SecurityConfig.java`.

## Consequences

* **The two go together and are only safe together.** CSRF exists because a
  browser attaches ambient credentials — a cookie — to a cross-site request
  automatically. Basic and Bearer credentials are not ambient: a cross-site
  page cannot make the victim's browser attach them. With no session and no
  cookie there is no ambient credential, so there is no CSRF surface.
* If a future change introduces a session cookie or cookie-based auth, **CSRF
  protection must come back on in the same commit**. That is the one way this
  decision becomes wrong, so it is stated here rather than left implicit.
* No session means no server-side state to replicate, so horizontal scaling is
  free and a pod restart costs nobody their login.
* Every request pays full authentication cost. For Basic that is a BCrypt
  verification, which is deliberately slow — measured in milliseconds, and the
  reason the cost factor is 10 rather than 14.

## Alternatives considered

* **Keep CSRF on with a token endpoint.** Real cost to every client, zero
  benefit for a non-cookie API, and it invites a "why does my curl fail"
  support burden that teaches people to disable security.
* **Sessions with sticky routing.** Reintroduces state for an API whose callers
  do not want it.
