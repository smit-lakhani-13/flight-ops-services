import { defineConfig, devices } from "@playwright/test";

// The browser tests drive the built console (`next start`) against a running
// service. CI starts the service from the jar first; locally, start it with
// `./mvnw spring-boot:run` and point API_BASE_URL at it if it is not on 8080.
const PORT = Number(process.env.CONSOLE_PORT ?? 3100);
const API_BASE_URL = process.env.API_BASE_URL ?? "http://localhost:8080";
const LAYOUT_ONLY = /layout\.spec\.ts$/;

function touch(name: string, width: number, height: number) {
  return {
    name,
    testMatch: LAYOUT_ONLY,
    use: { ...devices["Desktop Chrome"], viewport: { width, height }, isMobile: true, hasTouch: true },
  };
}

function desktop(name: string, width: number, height: number) {
  return { name, testMatch: LAYOUT_ONLY, use: { ...devices["Desktop Chrome"], viewport: { width, height } } };
}

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
  // desktop-1280 runs every spec. The other ten run only the layout spec:
  // four phones, four tablets either way up, and two wider desktops. Every
  // project is Chromium; the phones and tablets emulate a touch screen and a
  // mobile viewport, which is not the same as testing Safari.
  projects: [
    { name: "desktop-1280", use: { ...devices["Desktop Chrome"] } },
    touch("phone-320", 320, 568),
    touch("phone-375", 375, 667),
    touch("phone-390", 390, 844),
    touch("phone-430", 430, 932),
    touch("tablet-768", 768, 1024),
    touch("tablet-820", 820, 1180),
    touch("tablet-1024", 1024, 1366),
    touch("tablet-1180", 1180, 820),
    desktop("desktop-1440", 1440, 900),
    desktop("desktop-1920", 1920, 1080),
  ],
  webServer: {
    command: `npm run start -- --hostname 127.0.0.1 --port ${PORT}`,
    url: `http://127.0.0.1:${PORT}`,
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    env: { API_BASE_URL, NEXT_TELEMETRY_DISABLED: "1" },
  },
});
