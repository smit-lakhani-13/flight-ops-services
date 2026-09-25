# 17. The console reaches the API through its own server, not through CORS

Status: accepted (written 2026-09-25, in the same change as the console it
records)

## Context

The service is a JSON API for scripts and services, and
[ADR 0006](0006-stateless-sessions-no-csrf.md) turns off CSRF protection on
that basis: no cookie, no session, JSON-only writes and no CORS policy. It
names three changes that would make the decision wrong: a cookie or
cookie-based login, a form or `text/plain` body on a write, and a CORS policy
that allows credentials.

A browser console, to show booking, replays, the ten-caller race, the error
envelope and the health endpoints on one screen, has to send requests to the
API. A page on one origin calling an API on another needs the API to answer
CORS preflights, and a CORS policy is the third of those changes as soon as it
lets the page send credentials. A browser
also opens its own Basic login dialog when a 401 carries
`WWW-Authenticate: Basic`, and afterwards sends what was typed there with every
request to that origin.

So the question was how to give the service a browser client without touching
the API's security rules.

## Decision

The console is a Next.js application under `web/`, and the browser talks only
to the console's own origin. Every call goes to `/api/...` there, and a route
handler, `web/lib/proxy.ts#forward`, passes an allow-listed subset on to the
API at `API_BASE_URL`:

* `/api/v1/...` with `GET`, `HEAD`, `POST`, `PATCH` and `DELETE`;
* the actuator's health, liveness, readiness and metrics, read-only.

Anything else answers `404 CONSOLE_PATH_REFUSED` without reaching the API.
Four request headers go upstream: `Authorization`, `Content-Type`, `Accept` and
`X-Request-Id`. Six response headers come back, with `Location` cut to its
path. `WWW-Authenticate` and `Set-Cookie` never reach the browser. Bodies are
capped at 64 KiB, the upstream call at 15 s, and a request the browser marks
`Sec-Fetch-Site: cross-site` is refused with 403.

The credential stays in React state. It is never written to `localStorage`,
`sessionStorage` or a cookie, and the console's server copies it onto the one
upstream request without logging or keeping it. A reload signs out.

The ten-caller race is a second route, `web/lib/race.ts#runRace`, because a
browser queues requests beyond six per origin on HTTP/1.1 and ten `fetch()`
calls from a tab would not be concurrent.

The API does not change: no CORS mapping, no cookie, no new endpoint.

## Consequences

* **ADR 0006 still holds, and gains nothing to defend.** The API still sets no
  cookie, has no CORS policy and takes JSON-only writes. The console's origin
  sets no cookie either, and because the Basic challenge is dropped, the
  browser never caches a credential for it. A forged cross-site request to the
  console arrives with no `Authorization` header and meets the API's own 401,
  if the `Sec-Fetch-Site` check has not refused it first.
* **The proxy is where the console could widen what a browser reaches.** The
  allow-list is one function. Unit tests in `web/lib/proxy.test.ts` and the
  end-to-end tests assert that `env`, `prometheus`, the OpenAPI document and
  Swagger UI are refused, and that writes under the actuator are refused.
* **One more hop and one more process.** Every call crosses the console's
  server. For a console that is the right trade; for a script, the API is
  still one `curl` away.
* **A reload loses the credential.** That is the cost of storing nothing, and
  the end-to-end tests check that nothing survives it.
* **The console repeats one rule.** It copies the flight transition table from
  `src/main/java/com/smit/flightops/entity/FlightStatus.java#canTransitionTo`
  to decide which buttons to offer. `web/lib/transitions.test.ts` reads the
  Java file and fails if the copies disagree, and the service still decides:
  the console can send any status and show the 409.
* **Built and tested, never hosted.** CI lints, type-checks, unit-tests and
  builds the console, then drives it with Playwright against the service's own
  jar. There is no image, manifest or deploy step for it.

## Alternatives considered

* **CORS on the API, credentials allowed.** One allowed origin and
  `allowCredentials(true)`. It is the change ADR 0006 names, so CSRF
  protection would have to come back, and every client would carry a policy
  that only one page needs.
* **CORS on the API with a bearer token and no credentials flag.** No ambient
  credential, so CSRF stays out of it, but the API still gains a CORS policy,
  every write gains a preflight, and the page still needs a token. The
  service validates bearer tokens but issues none, so that means an identity
  provider this project does not have.
* **Serve the page from the Spring Boot application.** Same origin, no proxy.
  It puts a front-end build into the service's jar and image, needs public
  paths for static files in `SecurityConfig`, and still sends the Basic
  challenge to the browser.
* **A session cookie from a console login.** The console's server would keep
  the credential behind a cookie. That brings back an ambient credential, on
  the console's origin, and with it CSRF protection and a session store. It is
  the right shape for real users behind an identity provider; for a demo
  console with two fixed accounts it adds state to defend and nothing to show.
* **Plain HTML and `fetch`, no framework.** Less to install, but the race
  still needs server code, and routing, state and tests would be hand-rolled.
  Next.js route handlers put the proxy and the pages in one TypeScript
  project.
