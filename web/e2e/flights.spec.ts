import { expect, test } from "@playwright/test";
import {
  API_ACCOUNT,
  basic,
  createBooking,
  createFlight,
  go,
  openFlight,
  routeOf,
  serviceDown,
  signIn,
  uniqueFlightNumber,
} from "./support";

test("search narrows the list by airport", async ({ page }) => {
  await signIn(page);
  await go(page, "Flights");
  const search = page.getByRole("form", { name: "Search flights" });
  await search.getByLabel("Origin").fill("ORD");
  await search.getByRole("button", { name: "Search" }).click();

  // The list keeps the last answer on screen while a search is out, so wait
  // for UA123 to go before checking what came back in its place.
  const table = page.getByTestId("flight-table");
  await expect(table).not.toContainText("UA123");
  await expect(table).toContainText("UA456");
});

test("a new sort searches with the airports the fields show, not the last ones searched", async ({ page }) => {
  await signIn(page);
  await go(page, "Flights");
  const search = page.getByRole("form", { name: "Search flights" });
  await search.getByLabel("Origin").fill("ORD");
  await search.getByRole("button", { name: "Search" }).click();
  const table = page.getByTestId("flight-table");
  await expect(table).not.toContainText("UA123");

  // EWR is typed but not searched; the sort applies it.
  await search.getByLabel("Origin").fill("EWR");
  const sorted = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return url.pathname === "/api/v1/flights" && url.searchParams.get("sort") === "flightNumber,asc";
  });
  await search.getByLabel("Sort").selectOption("flightNumber,asc");
  expect(new URL((await sorted).url()).searchParams.get("origin")).toBe("EWR");
  await expect(table).not.toContainText("UA456");
  await expect(table).toContainText("UA123");
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
  // The banner leaves these messages to the fields, and every field has a
  // hint, so the description must be the API's message word for word.
  await expect(form.getByLabel("Flight number")).toHaveAccessibleDescription("must not be blank");
  await expect(form.getByLabel("Origin")).toHaveAccessibleDescription("must contain only letters");
  await expect(form.getByLabel("Seats")).toHaveAccessibleDescription("must be greater than or equal to 1");
  await expect(form.getByLabel("Departs")).toHaveAccessibleDescription("must not be null");
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
  // Try again went with the banner, so the focus is on the heading.
  await expect(page.getByRole("heading", { level: 1 })).toBeFocused();
});

test("a page of a flight's bookings that fails to arrive hands the focus to Refresh, which reads that page again", async ({ page, request }) => {
  // Eleven bookings make two pages of ten.
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  for (let i = 0; i < 11; i++) await createBooking(request, flightNumber);
  await signIn(page);
  await openFlight(page, flightNumber);
  const card = page.locator("section").filter({ has: page.getByRole("heading", { name: "Bookings on this flight" }) });
  const refresh = card.getByRole("button", { name: "Refresh" });
  const busy = card.locator('div[aria-busy="true"]');
  await expect(card.getByText("11 total · page 1 of 2")).toBeVisible();

  // The second page is held until the test lets it go, and the first stays on
  // screen meanwhile, marked busy. Then the service is down for it.
  const secondPage = (url: URL) =>
    url.pathname === "/api/v1/bookings" && url.searchParams.get("flightNumber") === flightNumber && url.searchParams.get("page") === "1";
  let release = () => {};
  const held = new Promise<void>((resolve) => (release = resolve));
  await page.route(secondPage, async (route) => {
    await held;
    await serviceDown(route);
  });
  await card.getByRole("button", { name: "Next" }).press("Enter");
  await expect(busy).toContainText("page 1 of 2");
  release();

  // The pager went with the table, so the focus is on Refresh, not the page.
  await expect(card.getByTestId("error-banner")).toHaveAttribute("data-code", "CONSOLE_UPSTREAM_UNREACHABLE");
  await expect(refresh).toBeFocused();

  // Refresh reads the second page, not the first, and keeps the focus.
  await page.unroute(secondPage);
  await refresh.press("Enter");
  await expect(card.getByText("11 total · page 2 of 2")).toBeVisible();
  await expect(busy).toHaveCount(0);
  await expect(refresh).toBeFocused();
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

test("Escape inside the cancel question keeps the flight and hands the focus back to Cancel flight", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  await signIn(page);
  await openFlight(page, flightNumber);

  const deletes: string[] = [];
  page.on("request", (sent) => {
    if (sent.method() === "DELETE") deletes.push(sent.url());
  });
  const cancelFlight = page.getByRole("button", { name: "Cancel flight" });
  const question = page.getByRole("group", { name: `Cancel ${flightNumber}? It cannot be undone.` });

  // From Keep it, where opening the question puts the focus.
  await cancelFlight.click();
  await expect(page.getByRole("button", { name: "Keep it" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(question).toHaveCount(0);
  await expect(cancelFlight).toBeEnabled();
  await expect(cancelFlight).toBeFocused();

  // From the other answer: Escape never means yes.
  await cancelFlight.click();
  await page.getByRole("button", { name: "Yes, cancel it" }).focus();
  await page.keyboard.press("Escape");
  await expect(question).toHaveCount(0);
  await expect(cancelFlight).toBeFocused();

  const kept = await request.get(`/api/v1/flights/${flightNumber}`, { headers: { Authorization: basic(API_ACCOUNT) } });
  expect(await kept.json()).toMatchObject({ status: "SCHEDULED" });
  expect(deletes).toEqual([]);
});

test("the widest flight row fits the table from 640 px up, so no seat count is cut at its edge", async ({ page, request }) => {
  // Ten characters and 850 seats are the most the API takes. Below 640 px the
  // departure and the status move into other cells; from there up the
  // departure wraps before the table would scroll inside its box, where the
  // box's edge cut 850/850 to 850/85.
  const flightNumber = `${uniqueFlightNumber()}XYZ`.slice(0, 10);
  const route = routeOf(flightNumber);
  await createFlight(request, flightNumber, 850, route);
  await signIn(page);
  await go(page, "Flights");
  const search = page.getByRole("form", { name: "Search flights" });
  await search.getByLabel("Origin").fill(route.origin);
  await search.getByLabel("Destination").fill(route.destination);
  await search.getByRole("button", { name: "Search" }).click();
  const table = page.getByTestId("flight-table");
  await expect(table.getByRole("link", { name: flightNumber })).toBeVisible();

  for (const width of [640, 700, 768, 800, 1024]) {
    await page.setViewportSize({ width, height: 900 });
    await expect
      .poll(() => table.evaluate((t) => t.parentElement!.scrollWidth - t.parentElement!.clientWidth), { message: `at ${width} px` })
      .toBe(0);
  }
});
