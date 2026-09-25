# Console

A browser front end for every operation of flight-ops-service. You sign in with
one of the service's two accounts, then list, create and move flights, book
seats with an idempotency key, replay a booking, send ten identical bookings at
once, cancel, and read the health endpoints and the meters. Every call is shown
in a request log with the request id the console sent and the one the service
echoed.

It is built and tested in CI on every push or pull request to `main`, never
hosted. [ADR 0017](../adr/0017-web-console.md) records why it is shaped the way
it is.

![Ten replays of one idempotency key from the console: ten 201s, one booking, one seat debited](../doc/assets/console-race.png)

## Run it

Node 24 (`.nvmrc`) and the service on port 8080. From the repository root, in
one terminal:

```bash
./mvnw spring-boot:run
```

and in a second:

```bash
cd web && npm ci && npm run build && npm start
```

Open <http://localhost:3000> and sign in as `api` / `dev-secret`, or as `ops` /
`dev-ops` for the actuator pages. Those are the service's default accounts;
[doc/api.md](../doc/api.md#authentication) explains them.

`API_BASE_URL` tells the console's server where the API is. It defaults to
`http://localhost:8080`, must be an origin with no path, and is read on each
request, so the same build can point anywhere.

## Pages

| Page | What it shows | Calls it makes |
|---|---|---|
| `/` | Sign-in, anonymous health, four guided demos | `GET /actuator/health`; `GET /api/v1/flights?size=1` to check the credential |
| `/flights` | Search by route, sort, page; create a flight with per-field errors | `GET /api/v1/flights`, `POST /api/v1/flights` |
| `/flights/{flightNumber}` | The flight, the moves its status allows, any status on request, cancel, its bookings | `GET`, `PATCH .../status` and `DELETE /api/v1/flights/{flightNumber}`; `GET /api/v1/bookings?flightNumber=` |
| `/book` | Book, replay the same key, change the body on the same key, the race | `GET /api/v1/flights/{flightNumber}`, `POST /api/v1/bookings`, and the console's own `POST /api/race` |
| `/bookings/{bookingId}` | One booking and its cancellation, which is idempotent | `GET` and `DELETE /api/v1/bookings/{bookingId}` |
| `/ops` | Health, liveness and readiness without credentials; health and seven meters as `ops` | `GET /actuator/health`, `.../liveness`, `.../readiness`, `/actuator/metrics/{name}` |

Each API error is shown with its code, its status, the service's message and,
for the codes the console knows, one line on what the code means. Field errors
land next to their fields.

## Design

The console has no UI dependencies: no component library, no icon pack and no
web font. The pages use the system font stack, the icons are inline SVGs in
`components/icons.tsx`, and the parts every page shares (buttons, fields,
cards, status badges, the seat bar, key and value lists, skeletons, empty
states and stat tiles) are in `components/ui.tsx`.

The tokens are in `app/globals.css`: one accent colour, two corner radii and
two shadows. Surfaces are slate and the accent is sky. Colour otherwise carries
meaning only: each flight status has its own, and an HTTP answer is green for
a 2xx, amber for a 4xx, orange for a 409 and red for a 5xx. Text keeps at
least 4.5:1 contrast in both schemes, and the dark scheme follows the system
setting, with no toggle. Every link, button and field shows an outline in the
accent colour when the keyboard reaches it, and the only motion is a colour
transition, left out when the system asks for less motion.

Below 1280 px, which takes in every phone and tablet viewport the tests use,
every button, nav link, field and select is at least 44 px tall, and every
field has 16 px text, so iOS does not zoom in when one takes focus. The
pages keep a denser desktop layout from 1280 px up. The navigation wraps
onto its own row rather than folding into a menu, and wide tables scroll
inside their own box instead of widening the page.

![A flight's page at 390 px wide: the header wraps onto three rows and every control is at least 44 px tall](../doc/assets/console-phone.png)

## How a call travels

```mermaid
flowchart LR
  page([Browser page]) -->|"/api/..., same origin"| route["Route handler<br/>web/app/api/"]
  route --> forward["forward():<br/>allow-list, headers, limits"]
  forward -->|"Authorization copied, nothing stored"| api[flight-ops-service]
```

The page never calls the API itself. It calls this console's own origin under
`/api/`, and `web/lib/proxy.ts#forward` passes an allow-listed subset on:

* `/api/v1/...` with `GET`, `HEAD`, `POST`, `PATCH` and `DELETE`;
* `/actuator/health`, its liveness and readiness groups and
  `/actuator/metrics/{name}`, read-only.

Everything else, the other actuator endpoints, the OpenAPI document and Swagger
UI included, answers `404 CONSOLE_PATH_REFUSED` without reaching the API. A
write under `/actuator` answers `405` with an `Allow` header.

Four request headers go upstream: `Authorization`, `Content-Type`, `Accept` and
`X-Request-Id`. Cookies, `Origin`, `Host` and forwarding headers do not. On the
way back only `Content-Type`, `Location`, `Retry-After`, `X-Request-Id`,
`Allow` and `Accept` pass, and every answer carries `Cache-Control: no-store`.
`Location` is cut to its path, so a new flight's link opens on the console.
The API's answer is read in full before it is relayed, so a stall or a dropped
connection part-way through becomes a `504` or a `502` rather than a cut-off
body.

`WWW-Authenticate` is dropped on purpose. Passed through, it would make the
browser open its own Basic dialog on every 401 and remember what was typed for
the whole origin.

The console's own refusals use the service's `{code, message, timestamp}`
shape, with codes that start with `CONSOLE_`:

| Status | Code | When |
|---|---|---|
| 400 | `CONSOLE_BAD_REQUEST` | The race body is not one JSON object |
| 403 | `CONSOLE_CROSS_SITE_REFUSED` | The browser marked the request `Sec-Fetch-Site: cross-site` |
| 404 | `CONSOLE_PATH_REFUSED` | The path is outside the allow-list |
| 405 | `CONSOLE_METHOD_REFUSED` | A method the path does not take, such as a write under `/actuator` or any `PUT` |
| 413 | `CONSOLE_BODY_TOO_LARGE` | A body over 64 KiB, counted as it arrives, so an endless one is cut off |
| 500 | `CONSOLE_MISCONFIGURED` | `API_BASE_URL` is not an origin |
| 502 | `CONSOLE_UPSTREAM_UNREACHABLE` | The API did not accept the connection, or dropped it before its answer was complete |
| 504 | `CONSOLE_UPSTREAM_TIMEOUT` | The API did not finish its answer within 15 s |

## Where the credential lives

In React state, and nowhere else: not in `localStorage`, `sessionStorage` or a
cookie. The console's server copies it onto the one upstream request and drops
it. A reload signs you out; that is the price of storing nothing, and the
end-to-end tests check it.

## Why the race runs on the server

A browser opens at most six HTTP/1.1 connections to one origin and queues the
rest, so ten `fetch()` calls from a tab are not ten concurrent requests.
`web/lib/race.ts#runRace` sends the ten from the console's server in the same
tick, with byte-identical bodies and one idempotency key, and returns every
answer. Act 4 of `scripts/demo.sh` runs the same experiment with
`xargs -P 10 curl`; the console shows it on one screen: ten `201`s with the
same booking id, and the flight's seat count down by the seats of one booking.

## Tests

| Command | What it runs |
|---|---|
| `npm run lint` | ESLint with Next.js's core web vitals and TypeScript rules, no warnings allowed |
| `npx tsc --noEmit` | The type-checker, strict, with unchecked index access |
| `npm test` | Vitest: the proxy, the race and the `/api` route against a stubbed `fetch`, the browser's API client and the sign-in probe, the error classifier, the request log, the resource hook, the shared parts in `components/ui.tsx`, the error banner, the transition control and the request log drawer in jsdom, and a test that reads `FlightStatus.java` and fails if the console's copy of the transition table drifts from it |
| `npm run e2e` | Playwright on Chromium against the built console and a running service: sign-in and sign-out, flights, bookings, replays and the race, validation, the proxy's refusals, the ops pages, and the layout of every page at eleven viewports |

`npm run e2e` starts the console itself on port 3100 and expects the service
at `API_BASE_URL`. Each run creates flights with fresh numbers, so it needs no
clean database. CI runs it against the service's own jar on the default H2
profile. `scripts/numbers.sh` prints how many tests each suite declares,
counting each test once rather than once per viewport.

The suite runs as eleven Playwright projects, all of them Chromium.
`desktop-1280` runs every spec. The other ten run only `e2e/layout.spec.ts`:
four phones (320, 375, 390 and 430 px wide) and four tablets (768, 820, 1024
and 1180 px), all with touch and a mobile viewport, and two wider desktops
(1440 and 1920 px). The layout spec walks every page through its links and
fails if a page scrolls sideways, a table is not in its own scroll box, or the
header loses its navigation, Sign out or Requests, and, on the touch projects,
if a control is under 44 px tall or a field's text is under 16 px. A second
test tabs from the top of the overview and checks that a link, a button and a
field each show a focus outline. Chromium emulating a phone is not Safari:
nothing here has run in WebKit.

## What it does not do

* **No hosting.** There is no Dockerfile, Compose service or manifest for the
  console, and nothing deploys it.
* **No login of its own.** It uses the service's accounts, and a reload signs
  out.
* **No server-side rendering of data.** Every page is a client component; the
  console's server only forwards.
* **No offline use or caching.** Each view asks the service again.
