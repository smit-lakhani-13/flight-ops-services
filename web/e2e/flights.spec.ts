import { expect, test, type Route } from "@playwright/test";
import { API_ACCOUNT, basic, createBooking, createFlight, go, openFlight, routeOf, signIn, uniqueFlightNumber } from "./support";

// What the console's own server answers while the service is down.
const serviceDown = (route: Route) =>
  route.fulfill({
    status: 502,
    json: { code: "CONSOLE_UPSTREAM_UNREACHABLE", message: "The console could not reach the API. Is the service running?" },
  });

test("search narrows the list by airport", async ({ page }) => {
  await signIn(page);
  await go(page, "Flights");
  const search = page.getByRole("form", { name: "Search flights" });
  await search.getByLabel("Origin").fill("ORD");
  await search.getByRole("button", { name: "Search" }).click();

  const table = page.getByTestId("flight-table");
  await expect(table).toContainText("UA456");
  await expect(table).not.toContainText("UA123");
});

test("a page of the list that fails to arrive offers Try again, which reads that page again", async ({ page, request }) => {
  // Eleven flights on one route make two pages of ten.
  const first = uniqueFlightNumber();
  const route = routeOf(first);
  await createFlight(request, first);
  for (let i = 0; i < 10; i++) await createFlight(request, uniqueFlightNumber(), 20, route);
  await signIn(page);
  await go(page, "Flights");
  const search = page.getByRole("form", { name: "Search flights" });
  await search.getByLabel("Origin").fill(route.origin);
  await search.getByLabel("Destination").fill(route.destination);
  await search.getByRole("button", { name: "Search" }).click();
  await expect(page.getByText(/total · page 1 of \d+/)).toBeVisible();

  // Next, pressed from the keyboard, asks for the second page while the
  // service is down. The pager goes with the table, and the focus goes to
  // Try again rather than back to the page.
  const secondPage = (url: URL) => url.pathname === "/api/v1/flights" && url.searchParams.get("page") === "1";
  await page.route(secondPage, serviceDown);
  await page.getByRole("button", { name: "Next" }).press("Enter");
  const tryAgain = page.getByRole("button", { name: "Try again" });
  await expect(page.getByTestId("error-banner")).toHaveAttribute("data-code", "CONSOLE_UPSTREAM_UNREACHABLE");
  await expect(tryAgain).toBeFocused();

  // Try again reads the second page, not the first, and hands the focus to
  // the page's heading once the list is back.
  await page.unroute(secondPage, serviceDown);
  await tryAgain.press("Enter");
  await expect(page.getByText(/total · page 2 of \d+/)).toBeVisible();
  await expect(tryAgain).toHaveCount(0);
  await expect(page.getByRole("heading", { level: 1 })).toBeFocused();
});

test("creating a flight shows the API's message per field, then opens the new flight", async ({ page }) => {
  await signIn(page);
  await go(page, "Flights");
  // The toggle keeps its name; aria-expanded says the form is open.
  const toggle = page.getByRole("button", { name: "New flight" });
  await toggle.click();
  await expect(toggle).toHaveAttribute("aria-expanded", "true");
  const form = page.getByRole("form", { name: "Create flight" });

  await form.getByLabel("Origin").fill("JF1");
  await form.getByLabel("Destination").fill("SFO");
  await form.getByLabel("Seats").fill("0");
  // An empty Departs goes as null, so the service names it too.
  await form.getByLabel("Departs").fill("");
  await form.getByRole("button", { name: "Create flight" }).click();

  await expect(form.getByLabel("Flight number")).toHaveAttribute("aria-invalid", "true");
  await expect(form.getByLabel("Origin")).toHaveAttribute("aria-invalid", "true");
  await expect(form.getByLabel("Seats")).toHaveAttribute("aria-invalid", "true");
  await expect(form.getByLabel("Departs")).toHaveAttribute("aria-invalid", "true");
  await expect(form.getByTestId("error-banner")).toHaveAttribute("data-code", "VALIDATION_FAILED");

  const flightNumber = uniqueFlightNumber();
  await form.getByLabel("Flight number").fill(flightNumber.toLowerCase());
  await form.getByLabel("Origin").fill("JFK");
  await form.getByLabel("Seats").fill("12");
  const departs = new Date(Date.now() + 7 * 24 * 3600 * 1000);
  const pad = (n: number) => String(n).padStart(2, "0");
  await form.getByLabel("Departs").fill(`${departs.getFullYear()}-${pad(departs.getMonth() + 1)}-${pad(departs.getDate())}T10:00`);
  await form.getByRole("button", { name: "Create flight" }).click();

  // The service stores it upper-case and answers with a Location, which the
  // console turns into its own page.
  await expect(page).toHaveURL(new RegExp(`/flights/${flightNumber}$`));
  await expect(page.getByTestId("flight-status")).toHaveText("SCHEDULED");
  await expect(page.getByTestId("seats-left")).toContainText("12/12");
});

test("Try again after an outage reads the flight and its bookings again", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  const bookingId = await createBooking(request, flightNumber);
  await signIn(page);

  // The service is down for this flight's two reads only.
  await page.route((url) => url.pathname === `/api/v1/flights/${flightNumber}`, serviceDown);
  await page.route((url) => url.pathname === "/api/v1/bookings" && url.searchParams.get("flightNumber") === flightNumber, serviceDown);
  await openFlight(page, flightNumber);
  await expect(page.getByTestId("error-banner")).toHaveAttribute("data-code", "CONSOLE_UPSTREAM_UNREACHABLE");

  await page.unrouteAll();
  await page.getByRole("button", { name: "Try again" }).click();
  await expect(page.getByTestId("flight-status")).toHaveText("SCHEDULED");
  await expect(page.getByTestId("booking-table")).toContainText(`#${bookingId}`);
  await expect(page.getByTestId("error-banner")).toHaveCount(0);
});

test("a Try again that fails as well is announced once, and a failed Refresh on the bookings still is", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  await signIn(page);
  await openFlight(page, flightNumber);
  await expect(page.getByTestId("flight-status")).toHaveText("SCHEDULED");
  const card = (title: string) => page.locator("section").filter({ has: page.getByRole("heading", { name: title }) });
  const bookingsBanner = card("Bookings on this flight").getByTestId("error-banner");
  const alerts = page.locator('[data-testid="error-banner"][role="alert"]');
  const flightUrl = (url: URL) => url.pathname === `/api/v1/flights/${flightNumber}`;
  const bookingsUrl = (url: URL) => url.pathname === "/api/v1/bookings" && url.searchParams.get("flightNumber") === flightNumber;

  // The service goes down. The cancel fails, and so does the read it starts,
  // which keeps the flight on screen under that read's quiet error.
  await page.route(flightUrl, serviceDown);
  await page.route(bookingsUrl, serviceDown);
  await page.getByRole("button", { name: "Cancel flight" }).click();
  await page.getByRole("button", { name: "Yes, cancel it" }).click();
  const tryAgain = page.getByRole("button", { name: "Try again" });
  await expect(tryAgain).toBeVisible();
  await expect(alerts).toHaveCount(1);

  // Try again, still down: the flight's read error is an alert again, and the
  // bookings card shows the same outage without announcing it a second time.
  await tryAgain.click();
  await expect(bookingsBanner).toBeVisible();
  await expect(tryAgain).not.toHaveAttribute("aria-busy", "true");
  await expect(alerts).toHaveCount(2);
  await expect(bookingsBanner).not.toHaveAttribute("role", "alert");

  // The flight comes back and the bookings stay down. The card's own Refresh
  // is a press of its own, so its failure is an alert.
  await page.unroute(flightUrl, serviceDown);
  await tryAgain.click();
  await expect(page.getByTestId("flight-status")).toHaveText("SCHEDULED");
  await expect(tryAgain).toHaveCount(0);
  await card("Bookings on this flight").getByRole("button", { name: "Refresh" }).click();
  await expect(bookingsBanner).toHaveAttribute("role", "alert");
});

test("a status the service allows moves the flight; any other gets its 409", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  await signIn(page);
  await openFlight(page, flightNumber);

  // From the keyboard: the move takes its own button away, and the focus
  // lands on the moves that follow rather than on the page.
  await page.getByRole("button", { name: "Move to BOARDING" }).press("Enter");
  await expect(page.getByTestId("flight-status")).toHaveText("BOARDING");
  await expect(page.getByRole("group", { name: "Allowed transitions" })).toBeFocused();

  await page.getByLabel("Send any status:").selectOption("SCHEDULED");
  const send = page.getByRole("button", { name: "Send", exact: true });
  await send.click();
  await expect(page.getByTestId("error-banner")).toHaveAttribute("data-code", "ILLEGAL_STATUS_TRANSITION");
  await expect(page.getByTestId("flight-status")).toHaveText("BOARDING");
  await expect(send).toBeFocused();
});

test("cancelling a flight asks first, then marks it CANCELLED and stops sales", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  await signIn(page);
  await openFlight(page, flightNumber);

  const deletes: string[] = [];
  page.on("request", (sent) => {
    if (sent.method() === "DELETE") deletes.push(sent.url());
  });
  const cancelFlight = page.getByRole("button", { name: "Cancel flight" });
  await cancelFlight.click();
  // The opener stays put, disabled, so a second click cannot land on an answer.
  await expect(cancelFlight).toBeDisabled();
  await page.getByRole("button", { name: "Keep it" }).click();
  await expect(page.getByRole("button", { name: "Keep it" })).toHaveCount(0);
  await expect(cancelFlight).toBeEnabled();
  await expect(cancelFlight).toBeFocused();
  const kept = await request.get(`/api/v1/flights/${flightNumber}`, { headers: { Authorization: basic(API_ACCOUNT) } });
  expect(await kept.json()).toMatchObject({ status: "SCHEDULED" });
  expect(deletes).toEqual([]);

  await cancelFlight.click();
  await page.getByRole("button", { name: "Yes, cancel it" }).click();
  await expect(page.getByTestId("flight-status")).toHaveText("CANCELLED");
  await expect(page.getByText("CANCELLED is terminal")).toBeVisible();
  expect(deletes).toHaveLength(1);

  // The page's "No" comes from its own copy of the rule; the service decides.
  const refused = await request.post("/api/v1/bookings", {
    headers: { Authorization: basic(API_ACCOUNT) },
    data: { flightNumber, passengerName: "Test Passenger", seats: 1, idempotencyKey: `e2e-${flightNumber}` },
  });
  expect(refused.status()).toBe(409);
  expect(await refused.json()).toMatchObject({ code: "FLIGHT_NOT_BOOKABLE" });
  await expect(page.getByText("No: a booking gets 409 FLIGHT_NOT_BOOKABLE")).toBeVisible();
});
