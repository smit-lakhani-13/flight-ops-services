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

* **The two go together.** CSRF works because a browser attaches a credential
  to a cross-site request by itself. A session cookie is the usual one, and
  this service sets no cookie and keeps no session. Basic is not immune: after
  its own login prompt, which the 401 here triggers with
  `WWW-Authenticate: Basic`, a browser remembers the credentials and sends them
  again. What closes that gap is the request shape. Every write needs a JSON
  body (`consumes = application/json` on each `POST` and the `PATCH`) or is a
  `DELETE`. A cross-site HTML form can send neither, and a script would need a
  CORS preflight, which this service never approves. A bearer token is never
  attached automatically.

* If a future change adds a session cookie or cookie-based auth, accepts a
  form or `text/plain` body on a write, or adds a CORS policy that allows
  credentials, **CSRF protection must come back on in the same commit**. These
  are the ways the decision becomes wrong, which is why I state them here.

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

**Correction (2026-09-23).** This record used to say Basic credentials are not
ambient, and that a session cookie is the only way the decision becomes wrong.
A browser caches Basic credentials after its login prompt and sends them again.
The protection rests on JSON-only writes and the absence of a CORS policy, so
a form body on a write or a CORS policy that allows credentials would also
make the decision wrong.

**Note (2026-09-25).** The browser console in `web/` does not change this
decision. It reaches the API through its own server
([ADR 0017](0017-web-console.md)), so the API still sets no cookie, has no
CORS policy and takes JSON-only writes. The console drops `WWW-Authenticate`,
so the browser never shows its Basic prompt or caches the credentials.
