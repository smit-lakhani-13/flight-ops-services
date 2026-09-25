import { describe, expect, it, vi } from "vitest";
import { apiRequest, buildPath, newId, type LogEntry } from "./api";

function capture(response: () => Response) {
  const requests: Request[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    requests.push(new Request(new URL(String(input), "http://console.test"), init));
    return response();
  }) as unknown as typeof fetch;
  return { fetchImpl, requests };
}

describe("buildPath", () => {
  it("prefixes /api and drops empty query values", () => {
    expect(buildPath("v1/flights", { origin: "EWR", destination: "", page: 0, size: undefined })).toBe(
      "/api/v1/flights?origin=EWR&page=0",
    );
    expect(buildPath("actuator/health")).toBe("/api/actuator/health");
  });
});

describe("newId", () => {
  it("returns a v4 UUID, with or without randomUUID", () => {
    const pattern = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
    expect(newId()).toMatch(pattern);
    const original = crypto.randomUUID;
    try {
      Object.defineProperty(crypto, "randomUUID", { value: undefined, configurable: true });
      expect(newId()).toMatch(pattern);
    } finally {
      Object.defineProperty(crypto, "randomUUID", { value: original, configurable: true });
    }
  });
});

describe("apiRequest", () => {
  it("sends credentials, a request id and JSON, and logs the call", async () => {
    const log: LogEntry[] = [];
    const { fetchImpl, requests } = capture(
      () =>
        new Response(JSON.stringify({ bookingId: 7 }), {
          status: 201,
          headers: { "Content-Type": "application/json", Location: "/api/v1/bookings/7", "X-Request-Id": "echo" },
        }),
    );
    const result = await apiRequest<{ bookingId: number }>(
      "POST",
      "v1/bookings",
      { authorization: "Basic abc", body: { seats: 1 } },
      { fetch: fetchImpl, onLog: (e) => log.push(e) },
    );

    const sent = requests[0]!;
    expect(sent.headers.get("authorization")).toBe("Basic abc");
    expect(sent.headers.get("content-type")).toBe("application/json");
    expect(sent.headers.get("x-request-id")).toMatch(/^web-/);
    expect(await sent.text()).toBe('{"seats":1}');
    expect(result).toMatchObject({ ok: true, status: 201, data: { bookingId: 7 }, location: "/api/v1/bookings/7" });
    expect(result.echoedRequestId).toBe("echo");
    expect(log).toHaveLength(1);
    expect(log[0]).toMatchObject({ method: "POST", path: "/api/v1/bookings", status: 201, code: null });
    expect(JSON.stringify(log)).not.toContain("Basic abc");
  });

  it("sends no Authorization or body when there is none", async () => {
    const { fetchImpl, requests } = capture(() => Response.json({ status: "UP" }));
    await apiRequest("GET", "actuator/health", { authorization: null }, { fetch: fetchImpl });
    expect(requests[0]!.headers.get("authorization")).toBeNull();
    expect(requests[0]!.headers.get("content-type")).toBeNull();
  });

  it("classifies an error envelope", async () => {
    const { fetchImpl } = capture(
      () =>
        new Response(JSON.stringify({ code: "INSUFFICIENT_SEATS", message: "Only 2 seats left" }), {
          status: 409,
          headers: { "Content-Type": "application/json" },
        }),
    );
    const result = await apiRequest("POST", "v1/bookings", { authorization: "x", body: {} }, { fetch: fetchImpl });
    expect(result.ok).toBe(false);
    expect(result.data).toBeNull();
    expect(result.error).toMatchObject({ kind: "conflict", code: "INSUFFICIENT_SEATS", message: "Only 2 seats left" });
  });

  it("handles an empty 204 and an empty 404", async () => {
    const empty = capture(() => new Response(null, { status: 204 }));
    expect((await apiRequest("DELETE", "v1/flights/UA1", { authorization: "x" }, { fetch: empty.fetchImpl })).ok).toBe(true);
    const missing = capture(() => new Response("", { status: 404 }));
    const result = await apiRequest("GET", "actuator/metrics/nope", { authorization: "x" }, { fetch: missing.fetchImpl });
    expect(result.error).toMatchObject({ kind: "notFound", code: "HTTP_404" });
  });

  it("reports a network failure as status 0", async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError("Failed to fetch");
    }) as unknown as typeof fetch;
    const result = await apiRequest("GET", "v1/flights", { authorization: null }, { fetch: fetchImpl });
    expect(result).toMatchObject({ ok: false, status: 0 });
    expect(result.error?.kind).toBe("network");
  });

  it("reports a body that fails after the headers as status 0, and logs it", async () => {
    const log: LogEntry[] = [];
    const broken = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('{"content":['));
        controller.error(new TypeError("network error"));
      },
    });
    const { fetchImpl } = capture(
      () => new Response(broken, { status: 200, headers: { "Content-Type": "application/json", "X-Request-Id": "echo" } }),
    );
    const result = await apiRequest("GET", "v1/flights", { authorization: "x" }, { fetch: fetchImpl, onLog: (e) => log.push(e) });
    expect(result).toMatchObject({ ok: false, status: 0, data: null, echoedRequestId: "echo" });
    expect(result.error?.kind).toBe("network");
    expect(log).toHaveLength(1);
    expect(log[0]).toMatchObject({ status: 0, echoedRequestId: "echo" });
  });

  it("rethrows the caller's own abort, before or during the body", async () => {
    const abort = () => new DOMException("The operation was aborted.", "AbortError");
    const before = vi.fn(async () => {
      throw abort();
    }) as unknown as typeof fetch;
    await expect(apiRequest("GET", "v1/flights", { authorization: null }, { fetch: before })).rejects.toMatchObject({
      name: "AbortError",
    });

    const aborted = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.error(abort());
      },
    });
    const { fetchImpl: during } = capture(() => new Response(aborted, { status: 200 }));
    await expect(apiRequest("GET", "v1/flights", { authorization: null }, { fetch: during })).rejects.toMatchObject({
      name: "AbortError",
    });
  });
});
