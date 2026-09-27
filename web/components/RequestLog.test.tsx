// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LogEntry } from "@/lib/api";
import { RequestLogProvider, useRequestLog } from "@/lib/request-log";
import { RequestLog } from "./RequestLog";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function entry(status: number, n: number): LogEntry {
  const requestId = `web-00000000-0000-4000-8000-00000000000${n}`;
  return {
    id: String(n),
    at: "2026-09-26T10:00:00Z",
    method: "GET",
    path: `/api/v1/flights/UA${n}`,
    status,
    code: status >= 400 ? "SOME_CODE" : null,
    requestId,
    echoedRequestId: status === 0 ? null : requestId,
    location: null,
    ms: 5,
  };
}

// Records the entries the way the api client does, oldest first.
function Seed({ entries }: { entries: LogEntry[] }) {
  const { record } = useRequestLog();
  useEffect(() => entries.forEach(record), [entries, record]);
  return null;
}

function renderLog(entries: LogEntry[], onClose = vi.fn()) {
  render(
    <RequestLogProvider>
      <Seed entries={entries} />
      <RequestLog onClose={onClose} />
    </RequestLogProvider>,
  );
  return onClose;
}

describe("RequestLog", () => {
  it("colours each row by its status class, 409 apart from the other 4xx", () => {
    renderLog([entry(201, 1), entry(404, 2), entry(409, 3), entry(503, 4), entry(0, 5)]);
    const rows = within(screen.getByTestId("request-log")).getAllByRole("row").slice(1);
    expect(rows.map((row) => row.dataset.statusClass)).toEqual(["none", "5xx", "409", "4xx", "2xx"]);
    const edges = rows.map((row) => row.querySelector("td")?.className.match(/border-l-(\w+)-\d+/)?.[1]);
    expect(edges).toEqual(["slate", "rose", "orange", "amber", "emerald"]);
  });

  it("shows the id it sent on its own, next to a button that copies it", () => {
    renderLog([entry(201, 1)]);
    const sent = screen.getByTestId("sent-id");
    expect(sent.textContent).toBe("web-00000000-0000-4000-8000-000000000001");
    expect(screen.getByRole("button", { name: `Copy ${sent.textContent}` })).toBeTruthy();
    expect(screen.getByTestId("echoed-id").textContent).toBe("same");
  });

  it("says so when nothing has been sent yet, with nothing to clear", () => {
    renderLog([]);
    expect(screen.queryByTestId("request-log")).toBeNull();
    expect(screen.getByText(/^No requests yet\./)).toBeTruthy();
    expect(screen.getByRole("button", { name: "Clear" })).toHaveProperty("disabled", true);
  });

  it("takes the focus when it opens, on Close", () => {
    renderLog([]);
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Close" }));
  });

  it("closes on Escape inside it or on Close, and asks for focus to go back", () => {
    const onClose = renderLog([entry(201, 1)]);
    fireEvent.keyDown(screen.getByRole("button", { name: /^Copy / }), { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(onClose).toHaveBeenCalledWith(true);
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onClose).toHaveBeenCalledTimes(2);
    expect(onClose).toHaveBeenLastCalledWith(true);
  });

  it("leaves Escape alone outside it, while an input method composes, and once handled", () => {
    const onClose = renderLog([]);
    const drawer = screen.getByRole("complementary", { name: "Request log" });
    fireEvent.keyDown(document.body, { key: "Escape" });
    fireEvent.keyDown(drawer, { key: "Escape", isComposing: true });
    fireEvent.keyDown(drawer, { key: "Enter" });
    const handled = new KeyboardEvent("keydown", { key: "Escape", bubbles: true, cancelable: true });
    handled.preventDefault();
    drawer.dispatchEvent(handled);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("keeps the focus in the drawer when Clear empties it", () => {
    renderLog([entry(201, 1)]);
    const clear = screen.getByRole("button", { name: "Clear" });
    clear.focus();
    fireEvent.click(clear);
    expect(screen.queryByTestId("request-log")).toBeNull();
    expect(clear).toHaveProperty("disabled", true);
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Close" }));
  });

  it("copies the id it sent, says so once, and says nothing when the clipboard refuses", async () => {
    const writeText = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error("denied"));
    vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText } });
    renderLog([entry(201, 1), entry(201, 2)]);
    const [second, first] = screen.getAllByRole("button", { name: /^Copy / }) as [HTMLElement, HTMLElement];
    const status = within(screen.getByRole("complementary", { name: "Request log" })).getByRole("status");
    expect(status.textContent).toBe("");

    await act(async () => fireEvent.click(first));
    expect(writeText).toHaveBeenLastCalledWith("web-00000000-0000-4000-8000-000000000001");
    expect(status.textContent).toBe("Copied web-00000000-0000-4000-8000-000000000001");
    // The button keeps its name and its title, so the news is said once.
    expect(first.getAttribute("aria-label")).toBe("Copy web-00000000-0000-4000-8000-000000000001");
    expect(first.getAttribute("title")).toBe("Copy");

    // A refusal leaves the line as it was: no error, and not emptied.
    await act(async () => fireEvent.click(second));
    expect(writeText).toHaveBeenLastCalledWith("web-00000000-0000-4000-8000-000000000002");
    expect(status.textContent).toBe("Copied web-00000000-0000-4000-8000-000000000001");
    expect(second.getAttribute("title")).toBe("Copy");
  });

  it("empties the copied line 1.5 s after the last copy, so the same id copied again is news", async () => {
    vi.useFakeTimers();
    try {
      vi.stubGlobal("navigator", { ...navigator, clipboard: { writeText: vi.fn().mockResolvedValue(undefined) } });
      renderLog([entry(201, 1), entry(201, 2)]);
      const [second, first] = screen.getAllByRole("button", { name: /^Copy / }) as [HTMLElement, HTMLElement];
      const status = within(screen.getByRole("complementary", { name: "Request log" })).getByRole("status");
      await act(async () => fireEvent.click(first));
      expect(status.textContent).toBe("Copied web-00000000-0000-4000-8000-000000000001");

      // A second copy starts the wait again, so the first one's timer cannot
      // empty the line early.
      act(() => vi.advanceTimersByTime(1000));
      await act(async () => fireEvent.click(second));
      expect(status.textContent).toBe("Copied web-00000000-0000-4000-8000-000000000002");
      act(() => vi.advanceTimersByTime(1499));
      expect(status.textContent).toBe("Copied web-00000000-0000-4000-8000-000000000002");
      act(() => vi.advanceTimersByTime(1));
      expect(status.textContent).toBe("");

      await act(async () => fireEvent.click(second));
      expect(status.textContent).toBe("Copied web-00000000-0000-4000-8000-000000000002");
    } finally {
      vi.useRealTimers();
    }
  });
});
