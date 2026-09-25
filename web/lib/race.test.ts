import { describe, expect, it, vi } from "vitest";
import { runRace } from "./race";
import { summariseRace } from "./race-summary";
import { RACE_SIZE } from "./race";
import type { RaceReport } from "./types";

const BASE = "http://api.test:8080";
const BOOKING = { flightNumber: "UA123", passengerName: "Jane Doe", seats: 1, idempotencyKey: "race-1" };

function raceRequest(body: string, headers: Record<string, string> = {}) {
  return new Request("http://console.test/api/race", {
    method: "POST",
    body,
    headers: { "Content-Type": "application/json", Authorization: "Basic YXBpOmRldi1zZWNyZXQ=", ...headers },
  });
}

describe("runRace", () => {
  it("sends ten identical bodies at once, each with its own request id", async () => {
    const seen: Request[] = [];
    let inFlight = 0;
    let maxInFlight = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = new Request(String(input), init);
      seen.push(request);
      inFlight += 1;
      maxInFlight = Math.max(maxInFlight, inFlight);
      await new Promise((resolve) => setTimeout(resolve, 5));
      inFlight -= 1;
      return new Response(JSON.stringify({ bookingId: 42 }), {
        status: 201,
        headers: {
          "Content-Type": "application/json",
          "X-Request-Id": request.headers.get("x-request-id") ?? "",
          Location: `${BASE}/api/v1/bookings/42`,
        },
      });
    }) as unknown as typeof fetch;

    const response = await runRace(raceRequest(JSON.stringify(BOOKING)), { fetch: fetchImpl, baseUrl: BASE });
    const report = (await response.json()) as RaceReport;

    expect(response.status).toBe(200);
    expect(seen).toHaveLength(RACE_SIZE);
    expect(maxInFlight).toBe(RACE_SIZE);
    expect(new Set(seen.map((r) => r.url))).toEqual(new Set([`${BASE}/api/v1/bookings`]));
    const bodies = await Promise.all(seen.map((r) => r.text()));
    expect(new Set(bodies).size).toBe(1);
    expect(JSON.parse(bodies[0]!)).toEqual(BOOKING);
    const ids = seen.map((r) => r.headers.get("x-request-id"));
    expect(new Set(ids).size).toBe(RACE_SIZE);
    expect(seen).toHaveLength(RACE_SIZE);
    expect(ids.every((id) => id?.startsWith("web-race-"))).toBe(true);
    expect(seen.every((r) => r.headers.get("authorization") === "Basic YXBpOmRldi1zZWNyZXQ=")).toBe(true);

    expect(report.rows).toHaveLength(RACE_SIZE);
    expect(report.rows.every((row) => row.echoedRequestId === row.requestId)).toBe(true);
    expect(report.rows[0]!.location).toBe("/api/v1/bookings/42");
    expect(summariseRace(report.rows)).toEqual({ statuses: { "201": RACE_SIZE }, bookingIds: [42] });
  });

  it("reports a failed call as a row, not a failed race", async () => {
    let call = 0;
    const fetchImpl = vi.fn(async () => {
      call += 1;
      if (call === 3) throw new TypeError("fetch failed");
      return new Response(JSON.stringify({ code: "IDEMPOTENCY_KEY_REUSED", message: "taken" }), {
        status: 409,
        headers: { "Content-Type": "application/json" },
      });
    }) as unknown as typeof fetch;

    const response = await runRace(raceRequest(JSON.stringify(BOOKING)), { fetch: fetchImpl, baseUrl: BASE });
    const report = (await response.json()) as RaceReport;

    expect(response.status).toBe(200);
    const failed = report.rows.filter((row) => row.status === 0);
    expect(failed).toHaveLength(1);
    expect(failed[0]!.body).toMatchObject({ code: "CONSOLE_UPSTREAM_UNREACHABLE" });
    expect(summariseRace(report.rows).statuses).toEqual({ "0": 1, "409": 9 });
  });

  it("refuses a body that is not one JSON object", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    for (const body of ["not json", "[1,2]", "null", "42"]) {
      const response = await runRace(raceRequest(body), { fetch: fetchImpl, baseUrl: BASE });
      expect(response.status, body).toBe(400);
      expect(await response.json()).toMatchObject({ code: "CONSOLE_BAD_REQUEST" });
    }
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("refuses a cross-site request", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const response = await runRace(raceRequest(JSON.stringify(BOOKING), { "Sec-Fetch-Site": "cross-site" }), {
      fetch: fetchImpl,
      baseUrl: BASE,
    });
    expect(response.status).toBe(403);
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("sends no Authorization when the page sent none", async () => {
    const seen: Request[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      seen.push(new Request(String(input), init));
      return new Response(null, { status: 401 });
    }) as unknown as typeof fetch;
    const request = new Request("http://console.test/api/race", {
      method: "POST",
      body: JSON.stringify(BOOKING),
      headers: { "Content-Type": "application/json" },
    });
    await runRace(request, { fetch: fetchImpl, baseUrl: BASE });
    expect(seen).toHaveLength(RACE_SIZE);
    expect(seen.every((r) => r.headers.get("authorization") === null)).toBe(true);
  });
});
