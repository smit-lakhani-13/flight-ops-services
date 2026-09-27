import { describe, expect, it } from "vitest";
import type { LogEntry } from "./api";
import { appendEntry, LOG_LIMIT } from "./request-log";

function entry(n: number): LogEntry {
  return {
    id: `web-${n}`,
    at: "2026-09-25T00:00:00Z",
    method: "GET",
    path: "/api/v1/flights",
    status: 200,
    code: null,
    requestId: `web-${n}`,
    echoedRequestId: `web-${n}`,
    location: null,
    ms: 1,
  };
}

describe("appendEntry", () => {
  it("keeps the newest twenty, newest first", () => {
    let entries: LogEntry[] = [];
    for (let n = 1; n <= LOG_LIMIT + 5; n += 1) entries = appendEntry(entries, entry(n));
    expect(entries).toHaveLength(LOG_LIMIT);
    expect(entries[0]!.id).toBe(`web-${LOG_LIMIT + 5}`);
    expect(entries.at(-1)!.id).toBe("web-6");
  });
});
