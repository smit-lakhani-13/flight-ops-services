import { expect, type APIRequestContext, type Page } from "@playwright/test";

// The default profile's two accounts, from application.yml. CI runs the jar
// with that profile, so nothing here is a secret.
export const API_ACCOUNT = { user: process.env.E2E_API_USER ?? "api", password: process.env.E2E_API_PASSWORD ?? "dev-secret" };
export const OPS_ACCOUNT = { user: process.env.E2E_OPS_USER ?? "ops", password: process.env.E2E_OPS_PASSWORD ?? "dev-ops" };

type Account = typeof API_ACCOUNT;
type NavLabel = "Overview" | "Flights" | "Book" | "Ops";

export function basic(account: Account): string {
  return `Basic ${Buffer.from(`${account.user}:${account.password}`).toString("base64")}`;
}

/**
 * The console keeps the credential in React state only, so a page.goto()
 * after this signs out again. Tests move between pages through the links.
 */
export async function signIn(page: Page, account: Account = API_ACCOUNT): Promise<void> {
  await page.goto("/");
  const form = page.getByRole("form", { name: "Sign in" });
  await form.getByLabel("User").fill(account.user);
  await form.getByLabel("Password").fill(account.password);
  await form.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByTestId("signed-in-as")).toContainText(account.user);
}

export async function go(page: Page, label: NavLabel): Promise<void> {
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: label, exact: true }).click();
}

let counter = 0;

/** A flight number no earlier run used: W, five base-36 digits of the clock, and a counter. */
export function uniqueFlightNumber(): string {
  counter += 1;
  const clock = (Date.now() % 36 ** 5).toString(36).toUpperCase().padStart(5, "0");
  return `W${clock}${counter}`;
}

const LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

/**
 * A test flight's route: from EWR to three letters hashed from its number.
 * Each flight so has a route almost no other flight shares, and openFlight
 * finds it on the list's first page however many flights earlier runs, or
 * scripts/demo.sh, left on a busy route such as EWR to SFO. The first
 * letter is never E, so the destination is never EWR itself, which the API
 * refuses: a flight's origin and destination must differ.
 */
export function routeOf(flightNumber: string): { origin: string; destination: string } {
  let hash = 0x811c9dc5;
  for (const char of flightNumber) hash = Math.imul(hash ^ char.charCodeAt(0), 0x01000193) >>> 0;
  let destination = "";
  for (const letters of [LETTERS.replace("E", ""), LETTERS, LETTERS]) {
    destination += letters[hash % letters.length];
    hash = Math.floor(hash / letters.length);
  }
  return { origin: "EWR", destination };
}

/** Creates a flight through the console's own proxy, as the pages would. */
export async function createFlight(request: APIRequestContext, flightNumber: string, totalSeats = 20): Promise<void> {
  const response = await request.post("/api/v1/flights", {
    headers: { Authorization: basic(API_ACCOUNT) },
    data: {
      flightNumber,
      ...routeOf(flightNumber),
      totalSeats,
      departureTime: new Date(Date.now() + 7 * 24 * 3600 * 1000).toISOString(),
    },
  });
  expect(response.status(), await response.text()).toBe(201);
}

/** Opens a flight's page through the flights list, keeping the session. */
export async function openFlight(page: Page, flightNumber: string): Promise<void> {
  await go(page, "Flights");
  const route = routeOf(flightNumber);
  const search = page.getByRole("form", { name: "Search flights" });
  await search.getByLabel("Origin").fill(route.origin);
  await search.getByLabel("Destination").fill(route.destination);
  // Should an earlier test flight share the route, this one is still first:
  // each departs a week after it was made, and the latest come first.
  await page.getByLabel("Sort").selectOption("departureTime,desc");
  await search.getByRole("button", { name: "Search" }).click();
  await page.getByTestId("flight-table").getByRole("link", { name: flightNumber, exact: true }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText(flightNumber);
}
