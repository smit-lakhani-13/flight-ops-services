// @vitest-environment jsdom
import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import type { RaceRow } from "@/lib/types";
import { RaceResult } from "./RaceResult";

afterEach(cleanup);

/** Ten callers the API refused alike, as on a flight with no seats left. */
function refusals(): RaceRow[] {
  return Array.from({ length: 10 }, (_, index) => ({
    index,
    status: 409,
    requestId: `web-race-${index}`,
    echoedRequestId: `web-race-${index}`,
    location: null,
    ms: 12,
    body: { code: "INSUFFICIENT_SEATS", message: "Only 0 seats left" },
  }));
}

describe("RaceResult", () => {
  it("gives the answers' scroller a tab stop and a name when no row links to a booking", () => {
    render(<RaceResult report={{ rows: refusals(), totalMs: 40 }} before={0} after={0} />);

    const scroller = screen.getByRole("region", { name: "Race answers" });
    const table = within(scroller).getByRole("table", { name: "Race answers" });
    expect(within(table).queryAllByRole("link")).toHaveLength(0);
    expect(within(table).getAllByText("INSUFFICIENT_SEATS")).toHaveLength(10);
    expect(scroller.tabIndex).toBe(0);
    expect(scroller.className).toContain("focus-visible:outline-2");
    scroller.focus();
    expect(document.activeElement).toBe(scroller);
  });
});
