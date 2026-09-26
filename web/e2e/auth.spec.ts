import { expect, test } from "@playwright/test";
import { go, OPS_ACCOUNT, signIn } from "./support";

test("a wrong password gets the API's 401 and clears the field", async ({ page }) => {
  await page.goto("/");
  const form = page.getByRole("form", { name: "Sign in" });
  await form.getByLabel("User").fill("api");
  await form.getByLabel("Password").fill("not-the-password");
  await form.getByRole("button", { name: "Sign in" }).click();

  await expect(form.getByTestId("error-banner")).toHaveAttribute("data-code", "UNAUTHENTICATED");
  await expect(form.getByLabel("Password")).toHaveValue("");
  await expect(page.getByText("Not signed in")).toBeVisible();
});

test("signing in opens the flight pages, and signing out closes them", async ({ page }) => {
  await signIn(page);
  await expect(page.getByText("Pick a demo to start.")).toBeVisible();
  await go(page, "Flights");
  await expect(page.getByTestId("flight-table")).toContainText("UA123");

  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByText("Not signed in")).toBeVisible();
  await expect(page.getByTestId("flight-table")).toHaveCount(0);
  await expect(page.getByRole("form", { name: "Sign in" })).toBeVisible();
});

test("a reload forgets the credential, because nothing stores it", async ({ page }) => {
  await signIn(page);
  await page.reload();
  await expect(page.getByText("Not signed in")).toBeVisible();

  const stored = await page.evaluate(() => ({
    cookies: document.cookie,
    local: window.localStorage.length,
    session: window.sessionStorage.length,
  }));
  expect(stored).toEqual({ cookies: "", local: 0, session: 0 });
});

test("the ops account signs in, and the flight pages show the API's 403", async ({ page }) => {
  await signIn(page, OPS_ACCOUNT);
  await expect(page.getByText("the flight pages will answer 403")).toBeVisible();
  await go(page, "Flights");
  await expect(page.getByTestId("error-banner")).toHaveAttribute("data-code", "FORBIDDEN");
});

test("the API's Basic challenge never reaches the browser", async ({ request }) => {
  const response = await request.get("/api/v1/flights");
  expect(response.status()).toBe(401);
  expect(response.headers()["www-authenticate"]).toBeUndefined();
  expect(await response.json()).toMatchObject({ code: "UNAUTHENTICATED" });
});
