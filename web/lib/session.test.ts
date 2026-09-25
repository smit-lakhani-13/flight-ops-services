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

/** Answers the sign-in probe with `status`, and keeps what was sent. */
function probeAnswers(status: number) {
  const sent: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      sent.push(new Request(new URL(String(input), "http://console.test"), init));
      const body = status === 200 ? { content: [] } : { code: status === 403 ? "FORBIDDEN" : "UNAUTHENTICATED", message: "No" };
      return Response.json(body, { status });
    }),
  );
  return sent;
}

describe("SessionProvider.signIn", () => {
  it("probes one page of flights and records an account with the API scopes", async () => {
    const sent = probeAnswers(200);
    const { result } = renderHook(() => useSession(), { wrapper });

    let error: unknown = "unset";
    await act(async () => {
      error = await result.current.signIn(" api ", "dev-secret");
    });

    expect(error).toBeNull();
    expect(result.current.session).toMatchObject({ user: "api", apiScopes: true });
    expect(sent).toHaveLength(1);
    expect(new URL(sent[0]!.url).pathname + new URL(sent[0]!.url).search).toBe("/api/v1/flights?size=1");
    expect(sent[0]!.headers.get("authorization")).toBe(`Basic ${btoa("api:dev-secret")}`);
  });

  it("signs in an account the API answers 403, and records that it lacks the scopes", async () => {
    probeAnswers(403);
    const { result } = renderHook(() => useSession(), { wrapper });
    await act(async () => {
      await result.current.signIn("OPS", "dev-ops");
    });
    expect(result.current.session).toMatchObject({ user: "OPS", apiScopes: false });
  });

  it("refuses a wrong password and keeps no session", async () => {
    probeAnswers(401);
    const { result } = renderHook(() => useSession(), { wrapper });
    let error: { code?: string } | null = null;
    await act(async () => {
      error = await result.current.signIn("api", "wrong");
    });
    expect(error).toMatchObject({ kind: "unauthenticated", code: "UNAUTHENTICATED" });
    expect(result.current.session).toBeNull();
  });
});
