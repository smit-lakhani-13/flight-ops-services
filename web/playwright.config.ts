import { defineConfig, devices } from "@playwright/test";

// The browser tests drive the built console (`next start`) against a running
// service. CI starts the service from the jar first; locally, start it with
// `./mvnw spring-boot:run` and point API_BASE_URL at it if it is not on 8080.
const PORT = Number(process.env.CONSOLE_PORT ?? 3100);
const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";

export default defineConfig({
  testDir: "./e2e",
  // One worker: the specs share one service and assert on its seat counts.
  workers: 1,
  retries: 0,
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `npm run start -- --hostname 127.0.0.1 --port ${PORT}`,
    url: `http://127.0.0.1:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    env: { API_BASE_URL, NEXT_TELEMETRY_DISABLED: "1" },
  },
});
