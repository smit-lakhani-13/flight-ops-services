// @vitest-environment jsdom
import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useResource } from "./use-resource";

afterEach(cleanup);

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}

describe("useResource", () => {
  it("keeps the newest answer and drops one that arrives late", async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const answers = [first, second];
    let calls = 0;
    const load = () => answers[calls++]!.promise;
    const { result } = renderHook(() => useResource(load));

    act(() => result.current.reload());
    await act(async () => second.resolve("page two"));
    await act(async () => first.resolve("page one"));

    expect(calls).toBe(2);
    expect(result.current.value).toBe("page two");
  });

  it("reports a load that rejects instead of leaving it unhandled", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const failure = new Error("bug");
    const load = () => Promise.reject(failure);
    const { result } = renderHook(() => useResource(load));

    await waitFor(() => expect(error).toHaveBeenCalledWith("A console load failed", failure));
    expect(result.current.value).toBeNull();
  });
});
