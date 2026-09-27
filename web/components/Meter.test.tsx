// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RequestLogProvider } from "@/lib/request-log";
import { SessionProvider } from "@/lib/session";
import { Meter } from "./Meter";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

/** The actuator in miniature: every read of the meter gets `answer`. */
function actuator(answer: () => Response) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async () => answer()),
  );
}

function renderMeter(name: string) {
  render(
    <RequestLogProvider>
      <SessionProvider>
        <Meter name={name} description="A meter." />
      </SessionProvider>
    </RequestLogProvider>,
  );
}

describe("Meter", () => {
  it("shows a gauge with no fresh count as the actuator's NaN, and keeps the page", async () => {
    // The actuator writes Double.NaN as a string, which has no toFixed.
    actuator(() => Response.json({ name: "outbox.pending", measurements: [{ statistic: "VALUE", value: "NaN" }], availableTags: [] }));
    renderMeter("outbox.pending");
    expect((await screen.findByTestId("meter-outbox.pending-VALUE")).textContent).toBe("NaN");
    expect(screen.getByText("no fresh count")).toBeTruthy();
  });

  it("shows a count as it is and a fraction to three places", async () => {
    actuator(() =>
      Response.json({
        name: "http.server.requests",
        baseUnit: "seconds",
        measurements: [
          { statistic: "COUNT", value: 3 },
          { statistic: "TOTAL_TIME", value: 0.12345 },
        ],
        availableTags: [],
      }),
    );
    renderMeter("http.server.requests");
    expect((await screen.findByTestId("meter-http.server.requests-COUNT")).textContent).toBe("3");
    expect(screen.getByTestId("meter-http.server.requests-TOTAL_TIME").textContent).toBe("0.123");
    expect(screen.queryByText("no fresh count")).toBeNull();
  });

  it("says a 404 means the service has no meter by that name, since every meter exists from startup", async () => {
    actuator(() => new Response(null, { status: 404 }));
    renderMeter("outbox.renamed");
    expect(await screen.findByText("The service has no meter by this name.")).toBeTruthy();
  });
});
