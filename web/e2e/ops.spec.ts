import { expect, test } from "@playwright/test";
import { API_ACCOUNT, basic, createFlight, go, OPS_ACCOUNT, signIn, uniqueFlightNumber } from "./support";

test("health, liveness and readiness need no credentials", async ({ page }) => {
  await page.goto("/");
  await go(page, "Ops");
  for (const card of ["health-actuator-health", "health-actuator-health-liveness", "health-actuator-health-readiness"]) {
    await expect(page.getByTestId(card).getByTestId("health-status")).toHaveText("UP");
  }
  await expect(page.getByTestId("health-components")).toHaveCount(0);
  await expect(page.getByRole("form", { name: "Sign in" })).toBeVisible();
});

test("the ops account sees the components and all seven meters, and one counts a booking and its replay", async ({ page, request }) => {
  await signIn(page, OPS_ACCOUNT);
  await go(page, "Ops");
  const signedIn = page.getByTestId("health-actuator-health-signed-in");
  await expect(signedIn.getByTestId("health-components")).toContainText("db");

  // The service registers every meter at startup, so each card shows a
  // number. A name the console and the service disagree on would show none.
  const meters = [
    ["bookings.booked", "COUNT"],
    ["bookings.cancelled", "COUNT"],
    ["bookings.lock_timeout", "COUNT"],
    ["outbox.pending", "VALUE"],
    ["outbox.dead", "VALUE"],
    ["outbox.publish", "COUNT"],
    ["outbox.pruned", "COUNT"],
  ];
  for (const [name, statistic] of meters) {
    await expect(page.getByTestId(`meter-${name}-${statistic}`)).toHaveText(/^\d+$/);
  }

  // Both outcomes are registered at zero from startup, so their elements show
  // either way; the counts are what prove the per-tag reads work. The specs
  // run on one worker, so nothing else books meanwhile: each count rises by
  // exactly one, where a read that lost its tag would rise by two.
  const whole = page.getByTestId("meter-bookings.booked-COUNT");
  const created = page.getByTestId("meter-bookings.booked-created");
  const replayed = page.getByTestId("meter-bookings.booked-replayed");
  const count = async (meter: typeof created) => {
    await expect(meter).toHaveText(/^\d+$/);
    return Number(await meter.textContent());
  };
  const before = { created: await count(created), replayed: await count(replayed) };

  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  const booking = { flightNumber, passengerName: "Test Passenger", seats: 1, idempotencyKey: `e2e-${flightNumber}` };
  for (let i = 0; i < 2; i += 1) {
    const response = await request.post("/api/v1/bookings", { headers: { Authorization: basic(API_ACCOUNT) }, data: booking });
    expect(response.status()).toBe(201);
  }

  await page.getByRole("button", { name: "Refresh", exact: true }).click();
  await expect.poll(() => count(created)).toBe(before.created + 1);
  await expect.poll(() => count(replayed)).toBe(before.replayed + 1);
  expect((await count(created)) + (await count(replayed))).toBe(await count(whole));
});

test("the api account gets the actuator's 403 on the meters", async ({ page }) => {
  await signIn(page);
  await go(page, "Ops");
  await expect(page.getByTestId("error-banner").first()).toHaveAttribute("data-code", "FORBIDDEN");
});

test("the request log shows each X-Request-Id the API echoed back", async ({ page }) => {
  await signIn(page);
  await go(page, "Flights");
  await expect(page.getByTestId("flight-table")).toBeVisible();
  const requests = page.getByRole("button", { name: /^Requests/ });
  await requests.click();
  await expect(requests).toHaveAttribute("aria-expanded", "true");
  // The drawer comes last on the page, so it takes the focus when it opens.
  await expect(page.getByRole("button", { name: "Close", exact: true })).toBeFocused();

  const log = page.getByTestId("request-log");
  const sent = log.getByTestId("sent-id");
  const echoed = log.getByTestId("echoed-id");
  await expect(sent.first()).toHaveText(/^web-[0-9a-f-]{36}$/);
  // Sign-in, the overview and the flight list make several calls, and each
  // carries its own id back. Each row is checked on its own, so an overview
  // answer that lands after the count adds a row and fails nothing.
  const rows = await sent.count();
  expect(rows).toBeGreaterThan(1);
  for (let i = 0; i < rows; i++) {
    await expect(sent.nth(i)).toHaveText(/^web-[0-9a-f-]{36}$/);
    await expect(echoed.nth(i)).toHaveText("same");
  }
  const ids = await sent.allTextContents();
  expect(new Set(ids).size).toBe(ids.length);
  await expect(log).not.toContainText("Basic ");

  await page.keyboard.press("Escape");
  await expect(log).toBeHidden();
  await expect(requests).toBeFocused();
  await expect(requests).toHaveAttribute("aria-expanded", "false");
});

test.describe("the proxy's allow-list", () => {
  test("refuses paths outside it without calling the API", async ({ request }) => {
    for (const path of ["/api/actuator/env", "/api/actuator/prometheus", "/api/v3/api-docs", "/api/swagger-ui/index.html"]) {
      const response = await request.get(path, { headers: { Authorization: basic(OPS_ACCOUNT) } });
      expect(response.status(), path).toBe(404);
      expect(await response.json()).toMatchObject({ code: "CONSOLE_PATH_REFUSED" });
    }
  });

  test("refuses writes to the actuator, cross-site calls and oversized bodies", async ({ request }) => {
    const write = await request.post("/api/actuator/health", { data: {} });
    expect(write.status()).toBe(405);
    expect(write.headers()["allow"]).toBe("GET, HEAD");

    const crossSite = await request.post("/api/v1/bookings", { headers: { "Sec-Fetch-Site": "cross-site" }, data: {} });
    expect(crossSite.status()).toBe(403);
    expect(await crossSite.json()).toMatchObject({ code: "CONSOLE_CROSS_SITE_REFUSED" });

    const tooLarge = await request.post("/api/v1/bookings", {
      headers: { "Content-Type": "application/json", Authorization: basic(API_ACCOUNT) },
      data: JSON.stringify({ passengerName: "x".repeat(70 * 1024) }),
    });
    expect(tooLarge.status()).toBe(413);
    expect(await tooLarge.json()).toMatchObject({ code: "CONSOLE_BODY_TOO_LARGE" });

    const badRace = await request.post("/api/race", { headers: { "Content-Type": "application/json" }, data: "[1, 2]" });
    expect(badRace.status()).toBe(400);
    expect(await badRace.json()).toMatchObject({ code: "CONSOLE_BAD_REQUEST" });

    const plainRace = await request.post("/api/race", {
      headers: { "Content-Type": "text/plain", Authorization: basic(API_ACCOUNT) },
      data: JSON.stringify({ flightNumber: "UA123", passengerName: "Jane Doe", seats: 1, idempotencyKey: "plain" }),
    });
    expect(plainRace.status()).toBe(415);
    expect(await plainRace.json()).toMatchObject({ code: "CONSOLE_UNSUPPORTED_MEDIA_TYPE" });
  });

  test("leaves a preflight to Next, which answers it with no CORS headers", async ({ request }) => {
    // ADR 0017: with no Access-Control-* header on the answer, a page on
    // another origin cannot send the console a write or read what it returns.
    const preflights = [
      ["/api/v1/bookings", "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT"],
      ["/api/actuator/env", "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT"],
      ["/api/race", "OPTIONS, POST"],
    ] as const;
    for (const [path, allow] of preflights) {
      const response = await request.fetch(path, {
        method: "OPTIONS",
        headers: {
          Origin: "https://elsewhere.example",
          "Access-Control-Request-Method": "POST",
          "Access-Control-Request-Headers": "authorization, content-type",
        },
      });
      expect(response.status(), path).toBe(204);
      expect(response.headers()["allow"], path).toBe(allow);
      expect(Object.keys(response.headers()).filter((name) => name.startsWith("access-control-")), path).toEqual([]);
    }

    // The race exports only POST, so Next refuses a read there itself: a bare
    // 405, outside the envelope and with no Allow header.
    const read = await request.get("/api/race");
    expect(read.status()).toBe(405);
    expect(read.headers()["allow"]).toBeUndefined();
    expect((await read.body()).length).toBe(0);
  });

  test("sends the console's security headers on pages and on proxied answers", async ({ request }) => {
    for (const path of ["/", "/api/v1/flights", "/api/actuator/env"]) {
      const headers = (await request.get(path)).headers();
      expect(headers["x-content-type-options"], path).toBe("nosniff");
      expect(headers["referrer-policy"], path).toBe("no-referrer");
      expect(headers["x-frame-options"], path).toBe("DENY");
      expect(headers["x-powered-by"], path).toBeUndefined();
    }
  });

  test("returns the API's Location as the console's own path", async ({ request }) => {
    const flightNumber = uniqueFlightNumber();
    const response = await request.post("/api/v1/flights", {
      headers: { Authorization: basic(API_ACCOUNT) },
      data: { flightNumber, origin: "EWR", destination: "LHR", totalSeats: 5, departureTime: new Date(Date.now() + 86_400_000).toISOString() },
    });
    expect(response.status()).toBe(201);
    expect(response.headers()["location"]).toBe(`/api/v1/flights/${flightNumber}`);
    expect(response.headers()["cache-control"]).toBe("no-store");
  });
});
