import { describe, expect, it } from "vitest";
import { summariseRace } from "./race-summary";
import type { RaceRow } from "./types";

function row(index: number, status: number, body: unknown): RaceRow {
  return { index, status, requestId: `r-${index}`, echoedRequestId: `r-${index}`, location: null, ms: 1, body };
}

describe("summariseRace", () => {
  it("counts statuses and collects distinct booking ids", () => {
    const rows = [
      row(0, 201, { bookingId: 7 }),
      ...Array.from({ length: 8 }, (_, i) => row(i + 1, 200, { bookingId: 7 })),
      row(9, 0, { code: "CONSOLE_UPSTREAM_TIMEOUT" }),
    ];
    expect(summariseRace(rows)).toEqual({ statuses: { "0": 1, "200": 8, "201": 1 }, bookingIds: [7] });
  });

  it("copes with empty and non-object bodies", () => {
    expect(summariseRace([row(0, 503, null), row(1, 502, "down")])).toEqual({
      statuses: { "502": 1, "503": 1 },
      bookingIds: [],
    });
  });
});
