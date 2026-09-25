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

test("the ops account sees the components and the booking meter split by outcome", async ({ page, request }) => {
  // One booking and its replay, so bookings.booked has both outcomes.
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  const booking = { flightNumber, passengerName: "Test Passenger", seats: 1, idempotencyKey: `e2e-${flightNumber}` };
  for (let i = 0; i < 2; i += 1) {
    const response = await request.post("/api/v1/bookings", { headers: { Authorization: basic(API_ACCOUNT) }, data: booking });
    expect(response.status()).toBe(201);
  }

  await signIn(page, OPS_ACCOUNT);
  await go(page, "Ops");
  const signedIn = page.getByTestId("health-actuator-health-signed-in");
  await expect(signedIn.getByTestId("health-components")).toContainText("db");
  await expect(page.getByTestId("meter-bookings.booked-created")).toBeVisible();
  await expect(page.getByTestId("meter-bookings.booked-replayed")).toBeVisible();
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
  await page.getByRole("button", { name: /^Requests/ }).click();

  const log = page.getByTestId("request-log");
  await expect(log.getByTestId("sent-id").first()).toHaveText(/^web-[0-9a-f-]{36}$/);
  await expect(log.getByTestId("echoed-id").first()).toHaveText("same");
  await expect(log).not.toContainText("Basic ");
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

    const badRace = await request.post("/api/race", { headers: { "Content-Type": "application/json" }, data: "[1, 2]" });
    expect(badRace.status()).toBe(400);
    expect(await badRace.json()).toMatchObject({ code: "CONSOLE_BAD_REQUEST" });
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
