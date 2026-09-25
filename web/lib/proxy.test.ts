import { describe, expect, it, vi } from "vitest";
import { forward, localLocation, MAX_BODY_BYTES, resolveTarget, upstreamOrigin } from "./proxy";

const BASE = "http://api.test:8080";

interface Captured {
  url: string;
  request: Request;
}

// The stub builds a real Request from what forward() passed, so undici
// validates the init exactly as it would on the wire: a GET with a body, for
// one, throws here as it would in production.
function stub(response: () => Response) {
  const calls: Captured[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    calls.push({ url, request: new Request(url, init) });
    return response();
  });
  return { fetchImpl: fetchImpl as unknown as typeof fetch, calls };
}

function browserRequest(path: string, init: RequestInit = {}) {
  return new Request(`http://console.test${path}`, init);
}

describe("resolveTarget", () => {
  it("maps v1 paths under /api", () => {
    expect(resolveTarget(["v1", "flights", "UA123"])?.path).toBe("/api/v1/flights/UA123");
    expect(resolveTarget(["v1", "bookings"])?.methods.has("POST")).toBe(true);
  });

  it("allows health, liveness, readiness and metrics, read-only", () => {
    for (const path of ["health", "health/liveness", "health/readiness", "metrics", "metrics/bookings.booked"]) {
      const target = resolveTarget(["actuator", ...path.split("/")]);
      expect(target?.path).toBe(`/actuator/${path}`);
      expect(target?.methods.has("POST")).toBe(false);
      expect(target?.methods.has("GET")).toBe(true);
    }
  });

  it("refuses everything else", () => {
    const refused = [
      [],
      ["v1"],
      ["actuator", "prometheus"],
      ["actuator", "env"],
      ["actuator", "metrics", "Bad Name"],
      ["actuator", "health", "liveness", "extra"],
      ["v3", "api-docs"],
      ["swagger-ui", "index.html"],
      ["error", "x"],
      ["v1", "..", "actuator"],
      ["v1", ".", "flights"],
      ["v1", "flights", "UA 123"],
      ["v1", "flights", "UA/123"],
      ["v1", "flights", "UA%2F123"],
    ];
    for (const segments of refused) {
      expect(resolveTarget(segments), segments.join("/")).toBeNull();
    }
  });
});

describe("upstreamOrigin", () => {
  it("defaults to localhost:8080 and accepts an origin", () => {
    expect(upstreamOrigin(undefined)).toBe("http://localhost:8080");
    expect(upstreamOrigin("")).toBe("http://localhost:8080");
    expect(upstreamOrigin("https://api.example.test/")).toBe("https://api.example.test");
  });

  it("refuses a path, a query or another scheme", () => {
    expect(() => upstreamOrigin("http://api.test/prefix")).toThrow();
    expect(() => upstreamOrigin("http://api.test/?a=1")).toThrow();
    expect(() => upstreamOrigin("file:///etc/passwd")).toThrow();
  });
});

describe("localLocation", () => {
  it("keeps the path of an upstream Location and drops a foreign one", () => {
    expect(localLocation(`${BASE}/api/v1/flights/UA999`, BASE)).toBe("/api/v1/flights/UA999");
    expect(localLocation("/api/v1/bookings/7", BASE)).toBe("/api/v1/bookings/7");
    expect(localLocation("https://elsewhere.test/api/v1/flights/X", BASE)).toBeNull();
  });
});

describe("forward", () => {
  it("forwards only the allow-listed request headers, and no body on a GET", async () => {
    const { fetchImpl, calls } = stub(() => Response.json({ ok: true }));
    const request = browserRequest("/api/v1/flights?origin=EWR&size=5", {
      headers: {
        Authorization: "Basic YXBpOmRldi1zZWNyZXQ=",
        Accept: "application/json",
        "X-Request-Id": "web-1",
        Cookie: "session=abc",
        Origin: "http://console.test",
        "X-Forwarded-For": "10.0.0.1",
      },
    });

    const response = await forward(request, ["v1", "flights"], { fetch: fetchImpl, baseUrl: BASE });

    expect(response.status).toBe(200);
    expect(calls).toHaveLength(1);
    const sent = calls[0]!;
    expect(sent.url).toBe(`${BASE}/api/v1/flights?origin=EWR&size=5`);
    expect(sent.request.method).toBe("GET");
    expect(sent.request.headers.get("authorization")).toBe("Basic YXBpOmRldi1zZWNyZXQ=");
    expect(sent.request.headers.get("x-request-id")).toBe("web-1");
    expect(sent.request.headers.get("cookie")).toBeNull();
    expect(sent.request.headers.get("origin")).toBeNull();
    expect(sent.request.headers.get("x-forwarded-for")).toBeNull();
    expect(sent.request.body).toBeNull();
  });

  it("forwards a POST body and the Content-Type", async () => {
    const { fetchImpl, calls } = stub(() => new Response(null, { status: 201 }));
    const body = JSON.stringify({ flightNumber: "UA123", passengerName: "Jane Doe", seats: 1, idempotencyKey: "k-1" });
    await forward(
      browserRequest("/api/v1/bookings", { method: "POST", body, headers: { "Content-Type": "application/json" } }),
      ["v1", "bookings"],
      { fetch: fetchImpl, baseUrl: BASE },
    );
    const sent = calls[0]!.request;
    expect(sent.method).toBe("POST");
    expect(sent.headers.get("content-type")).toBe("application/json");
    expect(await sent.text()).toBe(body);
  });

  it("sends no body on a DELETE", async () => {
    const { fetchImpl, calls } = stub(() => new Response(null, { status: 204 }));
    const response = await forward(browserRequest("/api/v1/flights/UA456", { method: "DELETE" }), ["v1", "flights", "UA456"], {
      fetch: fetchImpl,
      baseUrl: BASE,
    });
    expect(response.status).toBe(204);
    expect(response.body).toBeNull();
    expect(calls[0]!.request.body).toBeNull();
  });

  it("passes the allow-listed response headers and drops the challenge and cookies", async () => {
    const { fetchImpl } = stub(
      () =>
        new Response(JSON.stringify({ code: "UNAUTHENTICATED", message: "Authentication is required" }), {
          status: 401,
          headers: {
            "Content-Type": "application/json",
            "WWW-Authenticate": 'Basic realm="flight-ops-service"',
            "Set-Cookie": "JSESSIONID=1",
            "X-Request-Id": "web-2",
            "Strict-Transport-Security": "max-age=1",
          },
        }),
    );
    const response = await forward(browserRequest("/api/v1/flights"), ["v1", "flights"], { fetch: fetchImpl, baseUrl: BASE });

    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toBeNull();
    expect(response.headers.get("set-cookie")).toBeNull();
    expect(response.headers.get("strict-transport-security")).toBeNull();
    expect(response.headers.get("x-request-id")).toBe("web-2");
    expect(response.headers.get("cache-control")).toBe("no-store");
    expect(await response.json()).toMatchObject({ code: "UNAUTHENTICATED" });
  });

  it("rewrites Location to the console's own path and keeps Retry-After", async () => {
    const { fetchImpl } = stub(
      () => new Response("{}", { status: 201, headers: { Location: `${BASE}/api/v1/flights/UA999`, "Retry-After": "1" } }),
    );
    const response = await forward(
      browserRequest("/api/v1/flights", { method: "POST", body: "{}", headers: { "Content-Type": "application/json" } }),
      ["v1", "flights"],
      { fetch: fetchImpl, baseUrl: BASE },
    );
    expect(response.headers.get("location")).toBe("/api/v1/flights/UA999");
    expect(response.headers.get("retry-after")).toBe("1");
  });

  it("refuses a path outside the allow-list without calling the API", async () => {
    const { fetchImpl, calls } = stub(() => new Response("{}"));
    const response = await forward(browserRequest("/api/actuator/prometheus"), ["actuator", "prometheus"], {
      fetch: fetchImpl,
      baseUrl: BASE,
    });
    expect(response.status).toBe(404);
    expect(await response.json()).toMatchObject({ code: "CONSOLE_PATH_REFUSED" });
    expect(calls).toHaveLength(0);
  });

  it("refuses a write under actuator with 405 and an Allow header", async () => {
    const { fetchImpl, calls } = stub(() => new Response("{}"));
    const response = await forward(browserRequest("/api/actuator/health", { method: "POST", body: "{}" }), ["actuator", "health"], {
      fetch: fetchImpl,
      baseUrl: BASE,
    });
    expect(response.status).toBe(405);
    expect(response.headers.get("allow")).toBe("GET, HEAD");
    expect(calls).toHaveLength(0);
  });

  it("forwards HEAD with no body either way", async () => {
    const { fetchImpl, calls } = stub(() => new Response(null, { status: 200, headers: { "Content-Type": "application/json" } }));
    const response = await forward(browserRequest("/api/actuator/health", { method: "HEAD" }), ["actuator", "health"], {
      fetch: fetchImpl,
      baseUrl: BASE,
    });
    expect(response.status).toBe(200);
    expect(response.body).toBeNull();
    expect(calls[0]!.request.method).toBe("HEAD");
  });

  it("refuses a cross-site request", async () => {
    const { fetchImpl, calls } = stub(() => new Response("{}"));
    const response = await forward(
      browserRequest("/api/v1/bookings", { method: "POST", body: "{}", headers: { "Sec-Fetch-Site": "cross-site" } }),
      ["v1", "bookings"],
      { fetch: fetchImpl, baseUrl: BASE },
    );
    expect(response.status).toBe(403);
    expect(await response.json()).toMatchObject({ code: "CONSOLE_CROSS_SITE_REFUSED" });
    expect(calls).toHaveLength(0);
  });

  it("refuses an oversized body", async () => {
    const { fetchImpl, calls } = stub(() => new Response("{}"));
    const response = await forward(
      browserRequest("/api/v1/bookings", { method: "POST", body: "x".repeat(MAX_BODY_BYTES + 1) }),
      ["v1", "bookings"],
      { fetch: fetchImpl, baseUrl: BASE },
    );
    expect(response.status).toBe(413);
    expect(calls).toHaveLength(0);
  });

  it("answers 502 in the envelope when the API is unreachable", async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError("fetch failed");
    }) as unknown as typeof fetch;
    const response = await forward(browserRequest("/api/v1/flights"), ["v1", "flights"], { fetch: fetchImpl, baseUrl: BASE });
    expect(response.status).toBe(502);
    const body = await response.json();
    expect(body.code).toBe("CONSOLE_UPSTREAM_UNREACHABLE");
    expect(typeof body.timestamp).toBe("string");
  });

  it("answers 504 when the API does not answer in time", async () => {
    const fetchImpl = vi.fn(
      (_input: RequestInfo | URL, init?: RequestInit) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(init.signal?.reason));
        }),
    ) as unknown as typeof fetch;
    const response = await forward(browserRequest("/api/v1/flights"), ["v1", "flights"], {
      fetch: fetchImpl,
      baseUrl: BASE,
      timeoutMs: 20,
    });
    expect(response.status).toBe(504);
    expect(await response.json()).toMatchObject({ code: "CONSOLE_UPSTREAM_TIMEOUT" });
  });

  it("answers 500 when API_BASE_URL is not an origin", async () => {
    const { fetchImpl, calls } = stub(() => new Response("{}"));
    const response = await forward(browserRequest("/api/v1/flights"), ["v1", "flights"], {
      fetch: fetchImpl,
      baseUrl: "http://api.test/prefix",
    });
    expect(response.status).toBe(500);
    expect(await response.json()).toMatchObject({ code: "CONSOLE_MISCONFIGURED" });
    expect(calls).toHaveLength(0);
  });
});
