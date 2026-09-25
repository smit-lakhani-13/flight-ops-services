import { expect, test, type Page } from "@playwright/test";
import { createBooking, createFlight, go, openFlight, OPS_ACCOUNT, signIn, signInHere, uniqueFlightNumber } from "./support";

// The one spec every project in playwright.config.ts runs: four phones, four
// tablets, three desktops. It walks the pages by their links, because a
// page.goto() signs out, and on each one checks what a narrow or touch screen
// breaks first. All of it is Chromium's emulation of those screens.

/** Waits until no card is still reading, then lists what does not fit. */
async function checkLayout(page: Page, where: string, touch: boolean): Promise<void> {
  await expect(page.getByText(/^(Loading|Checking|Reading)\b.*…$/)).toHaveCount(0);
  await expect(page.getByRole("navigation", { name: "Main" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
  await expect(page.getByRole("button", { name: /^Requests/ })).toBeVisible();

  const problems = await page.evaluate((touch) => {
    const found: string[] = [];
    const root = document.documentElement;
    if (root.scrollWidth > root.clientWidth) {
      found.push(`the page is ${root.scrollWidth} px wide in a ${root.clientWidth} px viewport`);
    }
    const name = (el: HTMLElement) =>
      `${el.tagName.toLowerCase()} "${(el.getAttribute("aria-label") ?? el.getAttribute("name") ?? el.textContent ?? "").trim().slice(0, 40)}"`;
    for (const table of document.querySelectorAll("table")) {
      const parent = table.parentElement;
      if (!parent || getComputedStyle(parent).overflowX !== "auto") {
        found.push(`table ${table.dataset.testid ?? ""} does not scroll inside its own box`);
      }
    }
    if (touch) {
      const targets = document.querySelectorAll<HTMLElement>(
        'nav[aria-label="Main"] a, button, input:not([type="hidden"]), select',
      );
      for (const el of targets) {
        const box = el.getBoundingClientRect();
        if (box.width === 0 && box.height === 0) continue;
        if (box.height < 43.99) found.push(`${name(el)} is ${box.height.toFixed(1)} px tall`);
        const size = parseFloat(getComputedStyle(el).fontSize);
        if (el.matches("input, select") && size < 16) found.push(`${name(el)} has ${size} px text`);
      }
    }
    return found;
  }, touch);
  expect(problems, where).toEqual([]);
}

test("every page fits the screen, keeps its header and has controls a finger can hit", async ({ page, request, hasTouch }) => {
  test.setTimeout(120_000);
  const flightNumber = uniqueFlightNumber();
  await createFlight(request, flightNumber);
  const bookingId = await createBooking(request, flightNumber);

  await signIn(page);
  await checkLayout(page, "/", hasTouch);

  await go(page, "Flights");
  await expect(page.getByTestId("flight-table")).toBeVisible();
  await page.getByRole("button", { name: "New flight" }).click();
  await expect(page.getByRole("form", { name: "Create flight" })).toBeVisible();
  await checkLayout(page, "/flights with the create form open", hasTouch);

  await openFlight(page, flightNumber);
  const bookings = page.getByTestId("booking-table");
  await expect(bookings).toContainText(`#${bookingId}`);
  await checkLayout(page, "a flight with a booking", hasTouch);

  await bookings.getByRole("link", { name: `#${bookingId}`, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/bookings/${bookingId}$`));
  await expect(page.getByTestId("cancelled-at")).toBeVisible();
  await checkLayout(page, "that booking", hasTouch);

  await go(page, "Book");
  const form = page.getByRole("form", { name: "Booking" });
  await form.getByLabel("Flight number").fill(flightNumber);
  await form.getByRole("button", { name: "Book", exact: true }).click();
  await expect(page.getByTestId("booking-outcome")).toHaveCount(1);
  await form.getByRole("button", { name: "Race 10 callers on this key" }).click();
  await expect(page.getByTestId("race-result").locator("tbody tr")).toHaveCount(10);
  await checkLayout(page, "/book with an outcome and the race", hasTouch);

  const refresh = page.getByRole("button", { name: "Refresh", exact: true });
  await go(page, "Ops");
  await expect(page.getByTestId("error-banner").first()).toHaveAttribute("data-code", "FORBIDDEN");
  await expect(refresh).not.toHaveAttribute("aria-busy", "true");
  await checkLayout(page, "/ops as api", hasTouch);

  await page.getByRole("button", { name: "Sign out" }).click();
  await signInHere(page, OPS_ACCOUNT);
  await expect(page.getByTestId("meter-bookings.booked-created")).toBeVisible();
  await expect(refresh).not.toHaveAttribute("aria-busy", "true");
  await checkLayout(page, "/ops as ops", hasTouch);

  await page.getByRole("button", { name: /^Requests/ }).click();
  await expect(page.getByTestId("request-log")).toBeVisible();
  await checkLayout(page, "the request log", hasTouch);
});

test("the keyboard shows where it is on a link, a button and a field", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("form", { name: "Sign in" })).toBeVisible();

  const seen = new Set<string>();
  for (let press = 0; press < 30 && seen.size < 3; press += 1) {
    await page.keyboard.press("Tab");
    const focused = await page.evaluate(() => {
      const el = document.activeElement;
      if (!(el instanceof HTMLElement) || el === document.body) return null;
      const style = getComputedStyle(el);
      const kind = el.matches("a[href]") ? "link" : el.matches("button") ? "button" : el.matches("input, select, textarea") ? "field" : el.tagName;
      return {
        kind,
        name: (el.getAttribute("aria-label") ?? el.getAttribute("name") ?? el.textContent ?? "").trim().slice(0, 40),
        ring: style.outlineStyle !== "none" && parseFloat(style.outlineWidth) >= 2 && style.outlineColor !== "rgba(0, 0, 0, 0)",
      };
    });
    if (!focused) continue;
    expect(focused.ring, `${focused.kind} "${focused.name}" shows no focus indicator`).toBe(true);
    seen.add(focused.kind);
  }
  expect([...seen]).toEqual(expect.arrayContaining(["link", "button", "field"]));
});
