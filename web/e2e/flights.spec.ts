import { expect, test } from "@playwright/test";
import { API_ACCOUNT, basic, createFlight, go, openFlight, signIn, uniqueFlightNumber } from "./support";

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

test("creating a flight shows the API's message per field, then opens the new flight", async ({ page }) => {
  await signIn(page);
  await go(page, "Flights");
  await page.getByRole("button", { name: "New flight" }).click();
  const form = page.getByRole("form", { name: "Create flight" });

  await form.getByLabel("Origin").fill("JF1");
  await form.getByLabel("Destination").fill("SFO");
  await form.getByLabel("Seats").fill("0");
  await form.getByRole("button", { name: "Create flight" }).click();

  await expect(form.getByLabel("Flight number")).toHaveAttribute("aria-invalid", "true");
  await expect(form.getByLabel("Origin")).toHaveAttribute("aria-invalid", "true");
  await expect(form.getByLabel("Seats")).toHaveAttribute("aria-invalid", "true");
  await expect(form.getByTestId("error-banner")).toHaveAttribute("data-code", "VALIDATION_FAILED");

  const flightNumber = uniqueFlightNumber();
  await form.getByLabel("Flight number").fill(flightNumber.toLowerCase());
  await form.getByLabel("Origin").fill("JFK");
  await form.getByLabel("Seats").fill("12");
  await form.getByRole("button", { name: "Create flight" }).click();

  // The service stores it upper-case and answers with a Location, which the
  // console turns into its own page.
  await expect(page).toHaveURL(new RegExp(`/flights/${flightNumber}$`));
  await expect(page.getByTestId("flight-status")).toHaveText("SCHEDULED");
  await expect(page.getByTestId("seats-left")).toContainText("12/12");
});

test("a status the service allows moves the flight; any other gets its 409", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  await signIn(page);
  await openFlight(page, flightNumber);

  await page.getByRole("button", { name: "Move to BOARDING" }).click();
  await expect(page.getByTestId("flight-status")).toHaveText("BOARDING");

  await page.getByLabel("Send any status:").selectOption("SCHEDULED");
  await page.getByRole("button", { name: "Send", exact: true }).click();
  await expect(page.getByTestId("error-banner")).toHaveAttribute("data-code", "ILLEGAL_STATUS_TRANSITION");
  await expect(page.getByTestId("flight-status")).toHaveText("BOARDING");
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
  await page.getByRole("button", { name: "Cancel flight" }).click();
  await page.getByRole("button", { name: "Keep it" }).click();
  await expect(page.getByRole("button", { name: "Cancel flight" })).toBeVisible();
  const kept = await request.get(`/api/v1/flights/${flightNumber}`, { headers: { Authorization: basic(API_ACCOUNT) } });
  expect(await kept.json()).toMatchObject({ status: "SCHEDULED" });
  expect(deletes).toEqual([]);

  await page.getByRole("button", { name: "Cancel flight" }).click();
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
