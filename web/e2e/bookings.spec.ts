import { expect, test, type Page } from "@playwright/test";
import { API_ACCOUNT, basic, createFlight, go, signIn, uniqueFlightNumber } from "./support";

async function bookingForm(page: Page, flightNumber: string) {
  await go(page, "Book");
  const form = page.getByRole("form", { name: "Booking" });
  await form.getByLabel("Flight number").fill(flightNumber);
  return form;
}

test("a replay on the same key returns the first booking and debits nothing", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber, 20);
  await signIn(page);
  const form = await bookingForm(page, flightNumber);
  const outcomes = page.getByTestId("booking-outcome");

  await form.getByRole("button", { name: "Book", exact: true }).click();
  await expect(outcomes.first()).toHaveAttribute("data-action", "book");
  await expect(outcomes.first().getByTestId("seats-change")).toHaveText("20 → 19");
  const bookingId = await outcomes.first().getByTestId("booking-id").textContent();
  expect(bookingId).toMatch(/^booking #\d+$/);

  await form.getByRole("button", { name: "Replay the same key" }).click();
  await expect(outcomes).toHaveCount(2);
  await expect(outcomes.first()).toHaveAttribute("data-action", "replay");
  await expect(outcomes.first().getByTestId("booking-id")).toHaveText(bookingId!);
  await expect(outcomes.first().getByTestId("seats-change")).toHaveText("19 → 19");
  await expect(outcomes.first()).toContainText("same booking as the first Book");

  await form.getByRole("button", { name: "Same key, different body" }).click();
  await expect(outcomes).toHaveCount(3);
  await expect(outcomes.first()).toContainText("IDEMPOTENCY_KEY_REUSED");
  await expect(outcomes.first().getByTestId("seats-change")).toHaveText("19 → 19");
  await expect(form.getByTestId("error-banner")).toHaveAttribute("data-code", "IDEMPOTENCY_KEY_REUSED");
});

test("ten concurrent callers on one key make one booking and one debit", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber, 20);
  await signIn(page);
  const form = await bookingForm(page, flightNumber);

  await form.getByRole("button", { name: "Race 10 callers on this key" }).click();
  const race = page.getByTestId("race-result");
  await expect(race.locator("tbody tr")).toHaveCount(10);
  await expect(race.getByTestId("race-statuses")).toHaveText("10 × 201");
  await expect(race.getByTestId("race-distinct")).toHaveText(/^1 \(#\d+\)$/);
  await expect(race.getByTestId("race-seats")).toHaveText("20 → 19");
});

test("the race sends the caller's body as it came, so the API refuses what it refuses directly", async ({ request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber, 20);
  const headers = { "Content-Type": "application/json", Authorization: basic(API_ACCOUNT) };

  // Both bodies parse as JSON. The API refuses a seat count written as 2.0 and
  // a key sent twice, and a re-serialised body would have hidden both.
  for (const [tag, seats] of [["decimal", '"seats":2.0'], ["duplicate", '"seats":9,"seats":1']]) {
    const data = `{"flightNumber":"${flightNumber}","passengerName":"Raw Body","idempotencyKey":"raw-${tag}-${flightNumber}",${seats}}`;
    const direct = await request.post("/api/v1/bookings", { headers, data });
    expect(direct.status(), tag).toBe(400);

    const race = await request.post("/api/race", { headers, data });
    expect(race.status(), tag).toBe(200);
    const { rows } = (await race.json()) as { rows: { status: number; body: { code?: string } }[] };
    expect(rows.map((row) => row.status), tag).toEqual(Array(10).fill(400));
    expect(rows.every((row) => row.body.code === "MALFORMED_REQUEST"), tag).toBe(true);
  }

  const flight = await request.get(`/api/v1/flights/${flightNumber}`, { headers: { Authorization: basic(API_ACCOUNT) } });
  expect(((await flight.json()) as { availableSeats: number }).availableSeats).toBe(20);
});

test("the API's field messages appear beside the inputs they belong to", async ({ page }) => {
  await signIn(page);
  const form = await bookingForm(page, "UA123");
  await form.getByLabel("Passenger name").fill("   ");
  await form.getByLabel("Seats").fill("10");
  await form.getByRole("button", { name: "Book", exact: true }).click();

  // The banner leaves these fields' messages to the fields, so each is checked
  // word for word: a loose match would pass on the Seats hint alone.
  await expect(form.getByLabel("Passenger name")).toHaveAttribute("aria-invalid", "true");
  await expect(form.getByLabel("Passenger name")).toHaveAccessibleDescription("must not be blank");
  await expect(form.getByLabel("Seats")).toHaveAttribute("aria-invalid", "true");
  await expect(form.getByLabel("Seats")).toHaveAccessibleDescription("must be less than or equal to 9");
  await expect(form.getByTestId("error-banner")).toHaveAttribute("data-code", "VALIDATION_FAILED");
});

test("cancelling a booking returns its seats, and cancelling again changes nothing", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber, 20);
  await signIn(page);
  const form = await bookingForm(page, flightNumber);
  await form.getByLabel("Seats").fill("2");
  await form.getByRole("button", { name: "Book", exact: true }).click();
  await expect(page.getByTestId("booking-outcome").first().getByTestId("seats-change")).toHaveText("20 → 18");

  await page.getByTestId("booking-id").first().click();
  await expect(page).toHaveURL(/\/bookings\/\d+$/);
  await expect(page.getByTestId("cancelled-at")).toHaveText("—");

  await page.getByRole("button", { name: "Cancel booking" }).click();
  await expect(page.getByTestId("cancelled-at")).not.toHaveText("—");
  const cancelledAt = await page.getByTestId("cancelled-at").getAttribute("datetime");
  expect(cancelledAt).toBeTruthy();

  await page.getByRole("button", { name: "Cancel again" }).click();
  const answers = page.getByTestId("cancel-answers").locator("li");
  await expect(answers).toHaveCount(2);
  await expect(answers.nth(0)).toHaveText(`cancelledAt ${cancelledAt}`);
  await expect(answers.nth(1)).toHaveText(`cancelledAt ${cancelledAt}`);

  await page.getByRole("link", { name: flightNumber, exact: true }).click();
  await expect(page.getByTestId("seats-left")).toContainText("20/20");
});

test("an active booking on a flight that has departed cannot be cancelled, and keeps its seat", async ({ page, request }) => {
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber, 20);
  await signIn(page);
  const form = await bookingForm(page, flightNumber);
  await form.getByRole("button", { name: "Book", exact: true }).click();
  await expect(page.getByTestId("booking-outcome").first().getByTestId("seats-change")).toHaveText("20 → 19");
  await page.getByTestId("booking-id").first().click();
  await expect(page).toHaveURL(/\/bookings\/\d+$/);

  const departed = await request.patch(`/api/v1/flights/${flightNumber}/status`, {
    headers: { Authorization: basic(API_ACCOUNT) },
    data: { status: "DEPARTED" },
  });
  expect(departed.status(), await departed.text()).toBe(200);

  await page.getByRole("button", { name: "Cancel booking" }).click();
  await expect(page.getByTestId("error-banner")).toHaveAttribute("data-code", "BOOKING_NOT_CANCELLABLE");
  await expect(page.getByTestId("cancelled-at")).toHaveText("—");

  await page.getByRole("link", { name: flightNumber, exact: true }).click();
  await expect(page.getByTestId("seats-left")).toContainText("19/20");
});
