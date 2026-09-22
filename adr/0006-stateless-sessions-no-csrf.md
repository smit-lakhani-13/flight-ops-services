# 6. Stateless sessions, and CSRF protection off

Status: accepted (recorded 2026-09-22, decision taken in commit `e83d846`)

## Context

Spring Security's defaults suit a browser application with a session cookie:
it creates an `HttpSession` when it needs one and enables CSRF protection. This
is a JSON API that scripts and services call, with credentials or a bearer
token on every request.

Leaving the defaults in place would look safe and be wrong. Disabling CSRF is
the kind of line that gets flagged in review, so I wrote the reasoning down
here instead of arguing it once in a comment.

## Decision

I set `SessionCreationPolicy.STATELESS` and `csrf.disable()` together, in
`src/main/java/com/smit/flightops/config/SecurityConfig.java`.

## Consequences

* **The two go together.** Turning CSRF protection off is safe only because
  the service keeps no session. CSRF exists
  because a browser attaches ambient credentials (a cookie) to a cross-site
  request automatically. Basic and Bearer credentials are not ambient: a
  cross-site page cannot make the victim's browser attach them. With no session
  and no cookie there is no ambient credential, so there is no CSRF surface.

* If a future change adds a session cookie or cookie-based auth, **CSRF
  protection must come back on in the same commit**. This is the only way the
  decision becomes wrong, which is why I state it here.

* No session means no server-side state to replicate. Horizontal scaling is
  free, and a pod restart costs nobody their login.

* Every request pays the full authentication cost. For Basic that is a BCrypt
  verification, which is slow by design and takes milliseconds. It is also
  why the BCrypt cost factor is 10 instead of 14.

## Alternatives considered

* **A CSRF token endpoint.** Keeping CSRF on this way costs every client real
  work, for no benefit on an API with no cookies. It also invites a "why does
  my curl fail" support burden that teaches people to disable security.

* **Sessions with sticky routing.** Reintroduces state for an API whose callers
  do not want it.
