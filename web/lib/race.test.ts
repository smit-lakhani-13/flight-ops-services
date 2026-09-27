import { describe, expect, it, vi } from "vitest";
import { MAX_BODY_BYTES } from "./proxy";
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

  it("refuses a body not sent as application/json, as the API would", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    for (const type of [
      "text/plain",
      "application/x-www-form-urlencoded",
      "multipart/form-data; boundary=x",
      "application/json-patch+json",
    ]) {
      const response = await runRace(raceRequest(JSON.stringify(BOOKING), { "Content-Type": type }), {
        fetch: fetchImpl,
        baseUrl: BASE,
      });
      expect(response.status, type).toBe(415);
      expect(await response.json()).toMatchObject({ code: "CONSOLE_UNSUPPORTED_MEDIA_TYPE" });
    }
    expect(fetchImpl).not.toHaveBeenCalled();

    const accepted = vi.fn(async () => new Response(null, { status: 201 })) as unknown as typeof fetch;
    const response = await runRace(
      raceRequest(JSON.stringify(BOOKING), { "Content-Type": "application/json; charset=utf-8" }),
      { fetch: accepted, baseUrl: BASE },
    );
    expect(response.status).toBe(200);
    expect(accepted).toHaveBeenCalledTimes(RACE_SIZE);
  });

  it("refuses a body one byte over the proxy's limit with the proxy's words", async () => {
    const fetchImpl = vi.fn() as unknown as typeof fetch;
    const response = await runRace(raceRequest("x".repeat(MAX_BODY_BYTES + 1)), { fetch: fetchImpl, baseUrl: BASE });
    expect(response.status).toBe(413);
    expect(await response.json()).toMatchObject({
      code: "CONSOLE_BODY_TOO_LARGE",
      message: `The console forwards bodies of at most ${MAX_BODY_BYTES} bytes.`,
    });
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

  it("sends the caller's bytes and media type, not a re-serialised body", async () => {
    const seen: Request[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      seen.push(new Request(String(input), init));
      return new Response(null, { status: 400 });
    }) as unknown as typeof fetch;
    // A duplicate key, a seat count written as 2.0 and a byte that is not
    // UTF-8 (0xE9 in place of the "?"): JSON.parse takes all three, the API
    // refuses each one.
    const sent = new TextEncoder().encode(
      '{"flightNumber":"UA123","passengerName":"Ren?e","seats":2.0,"seats":1,"idempotencyKey":"k"}',
    );
    sent[sent.indexOf(0x3f)] = 0xe9;
    const type = "application/json; charset=utf-8";
    const request = new Request("http://console.test/api/race", {
      method: "POST",
      body: sent,
      headers: { "Content-Type": type },
    });

    await runRace(request, { fetch: fetchImpl, baseUrl: BASE });

    expect(seen).toHaveLength(RACE_SIZE);
    const bodies = await Promise.all(seen.map(async (r) => new Uint8Array(await r.arrayBuffer())));
    expect(bodies.every((body) => body.length === sent.length && body.every((b, i) => b === sent[i]))).toBe(true);
    expect(seen.every((r) => r.headers.get("content-type") === type)).toBe(true);
  });
});
