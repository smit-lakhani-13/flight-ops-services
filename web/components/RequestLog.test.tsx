// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { useEffect } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { LogEntry } from "@/lib/api";
import { RequestLogProvider, useRequestLog } from "@/lib/request-log";
import { RequestLog } from "./RequestLog";

afterEach(cleanup);

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

  it("closes on Escape and asks for focus to go back", () => {
    const onClose = renderLog([]);
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).toHaveBeenCalledWith(true);
  });
});
