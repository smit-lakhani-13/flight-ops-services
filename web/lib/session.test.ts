// @vitest-environment jsdom
import { act, cleanup, renderHook } from "@testing-library/react";
import { createElement, type ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RequestLogProvider } from "./request-log";
import { SessionProvider, useSession } from "./session";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function wrapper({ children }: { children: ReactNode }) {
  return createElement(RequestLogProvider, null, createElement(SessionProvider, null, children));
}

const OPS_HEALTH = { status: "UP", components: { db: { status: "UP" } } };
const DOWN_OPS_HEALTH = { status: "DOWN", components: { db: { status: "DOWN" } } };

/**
 * Answers the sign-in probes: health with `health` and its body, one page of
 * flights with `flights`. Keeps what was sent.
 */
function probesAnswer(health: number, healthBody: object | null, flights = 200) {
  const sent: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = new Request(new URL(String(input), "http://console.test"), init);
      sent.push(request);
      if (new URL(request.url).pathname === "/api/actuator/health") {
        return Response.json(healthBody ?? { code: "UNAUTHENTICATED", message: "No" }, { status: health });
      }
      const body =
        flights === 200 ? { content: [] } : { code: flights === 403 ? "FORBIDDEN" : "DATABASE_UNAVAILABLE", message: "No" };
      return Response.json(body, { status: flights });
    }),
  );
  return sent;
}

function pathOf(request: Request): string {
  const url = new URL(request.url);
  return url.pathname + url.search;
}

describe("SessionProvider.signIn", () => {
  it("checks the password on health, then probes one page of flights for the API scopes", async () => {
    const sent = probesAnswer(200, { status: "UP" });
    const { result } = renderHook(() => useSession(), { wrapper });

    let error: unknown = "unset";
    await act(async () => {
      error = await result.current.signIn(" api ", "dev-secret");
    });

    expect(error).toBeNull();
    expect(result.current.session).toMatchObject({ user: "api", apiScopes: true });
    expect(sent.map(pathOf)).toEqual(["/api/actuator/health", "/api/v1/flights?size=1"]);
    for (const request of sent) expect(request.headers.get("authorization")).toBe(`Basic ${btoa("api:dev-secret")}`);
  });

  it("signs in an account that sees the health components without asking the API, so nothing is refused", async () => {
    const sent = probesAnswer(200, OPS_HEALTH, 403);
    const { result } = renderHook(() => useSession(), { wrapper });
    await act(async () => {
      await result.current.signIn("OPS", "dev-ops");
    });
    expect(result.current.session).toMatchObject({ user: "OPS", apiScopes: false });
    expect(sent.map(pathOf)).toEqual(["/api/actuator/health"]);
  });

  it("signs in ops while a component is down, because health's 503 still proves the password", async () => {
    probesAnswer(503, DOWN_OPS_HEALTH);
    const { result } = renderHook(() => useSession(), { wrapper });
    let error: unknown = "unset";
    await act(async () => {
      error = await result.current.signIn("ops", "dev-ops");
    });
    expect(error).toBeNull();
    expect(result.current.session).toMatchObject({ user: "ops", apiScopes: false });
  });

  it("still signs in an account the flights probe answers 403, and records that it lacks the scopes", async () => {
    probesAnswer(200, { status: "UP" }, 403);
    const { result } = renderHook(() => useSession(), { wrapper });
    await act(async () => {
      await result.current.signIn("someone", "secret");
    });
    expect(result.current.session).toMatchObject({ user: "someone", apiScopes: false });
  });

  it("refuses the API account while the database is down, with the flights probe's 503", async () => {
    probesAnswer(503, { status: "DOWN" }, 503);
    const { result } = renderHook(() => useSession(), { wrapper });
    let error: { code?: string } | null = null;
    await act(async () => {
      error = await result.current.signIn("api", "dev-secret");
    });
    expect(error).toMatchObject({ code: "DATABASE_UNAVAILABLE" });
    expect(result.current.session).toBeNull();
  });

  it("refuses a wrong password on the health probe and keeps no session", async () => {
    const sent = probesAnswer(401, null);
    const { result } = renderHook(() => useSession(), { wrapper });
    let error: { code?: string } | null = null;
    await act(async () => {
      error = await result.current.signIn("api", "wrong");
    });
    expect(error).toMatchObject({ kind: "unauthenticated", code: "UNAUTHENTICATED" });
    expect(result.current.session).toBeNull();
    expect(sent.map(pathOf)).toEqual(["/api/actuator/health"]);
  });
});
