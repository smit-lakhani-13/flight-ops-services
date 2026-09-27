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

  it("is pending until a reload answers, and keeps the last answer meanwhile", async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const answers = [first, second];
    let calls = 0;
    const load = () => answers[calls++]!.promise;
    const { result } = renderHook(() => useResource(load));

    expect(result.current.pending).toBe(true);
    await act(async () => first.resolve("page one"));
    expect(result.current.pending).toBe(false);

    act(() => result.current.reload());
    expect(result.current.pending).toBe(true);
    expect(result.current.value).toBe("page one");
    await act(async () => second.resolve("page one again"));
    expect(result.current.pending).toBe(false);
    expect(result.current.value).toBe("page one again");
  });

  it("is pending again when the load changes, as a new search does", async () => {
    const pageOne = deferred<string>();
    const pageTwo = deferred<string>();
    const loadOne = () => pageOne.promise;
    const loadTwo = () => pageTwo.promise;
    const { result, rerender } = renderHook(({ load }) => useResource(load), { initialProps: { load: loadOne } });

    await act(async () => pageOne.resolve("page one"));
    expect(result.current.pending).toBe(false);

    rerender({ load: loadTwo });
    expect(result.current.pending).toBe(true);
    expect(result.current.value).toBe("page one");
    await act(async () => pageTwo.resolve("page two"));
    expect(result.current.pending).toBe(false);
    expect(result.current.value).toBe("page two");
  });

  it("keeps a write's answer over a load that started before it", async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    const third = deferred<string>();
    const answers = [first, second, third];
    let calls = 0;
    const load = () => answers[calls++]!.promise;
    const { result, rerender } = renderHook(() => useResource(load));
    await act(async () => first.resolve("SCHEDULED"));
    const set = result.current.set;

    // A read goes out, a write answers, then the read answers late.
    act(() => result.current.reload());
    act(() => result.current.set("BOARDING"));
    await act(async () => second.resolve("SCHEDULED"));
    expect(result.current.value).toBe("BOARDING");
    expect(result.current.pending).toBe(false);

    // A read sent after the write is applied as usual.
    act(() => result.current.reload());
    await act(async () => third.resolve("DEPARTED"));
    expect(result.current.value).toBe("DEPARTED");
    rerender();
    expect(result.current.set).toBe(set);
  });

  it("reports a load that rejects instead of leaving it unhandled", async () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined);
    const failure = new Error("bug");
    const load = () => Promise.reject(failure);
    const { result } = renderHook(() => useResource(load));

    await waitFor(() => expect(error).toHaveBeenCalledWith("A console load failed", failure));
    expect(result.current.value).toBeNull();
    expect(result.current.pending).toBe(false);
  });
});
