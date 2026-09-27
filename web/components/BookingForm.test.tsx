// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RequestLogProvider } from "@/lib/request-log";
import { SessionProvider } from "@/lib/session";
import { BookingForm } from "./BookingForm";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

/**
 * A booking API in miniature: a new key makes a booking, the same key and
 * body gets that booking back, both with 201 as the real API answers, and a
 * flight read answers with the seats left. `hold` makes the next booking wait
 * until it is released.
 */
function fakeApi() {
  const byKey = new Map<string, { body: string; bookingId: number }>();
  let nextBooking = 1;
  let held: Promise<void> | null = null;

  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = new URL(String(input), "http://console.test").pathname;
      const echo = { "X-Request-Id": new Headers(init?.headers).get("x-request-id") ?? "" };
      if (init?.method !== "POST") {
        return Response.json({ flightNumber: "UA1", availableSeats: 100 }, { headers: echo });
      }
      expect(path).toBe("/api/v1/bookings");
      if (held) await held;
      const body = String(init.body);
      const { idempotencyKey } = JSON.parse(body) as { idempotencyKey: string };
      const known = byKey.get(idempotencyKey);
      if (known && known.body !== body) {
        return Response.json({ code: "IDEMPOTENCY_KEY_REUSED", message: "Different body" }, { status: 409, headers: echo });
      }
      const bookingId = known?.bookingId ?? nextBooking++;
      byKey.set(idempotencyKey, { body, bookingId });
      return Response.json(
        { bookingId, flightNumber: "UA1", passengerName: "Test Passenger", seats: 1, createdAt: "2026-09-26T10:00:00Z", cancelledAt: null },
        { status: 201, headers: { ...echo, Location: `/api/v1/bookings/${bookingId}` } },
      );
    }),
  );

  return {
    hold() {
      let release = () => {};
      held = new Promise((resolve) => (release = resolve));
      return () => {
        held = null;
        release();
      };
    },
  };
}

function renderForm() {
  render(
    <RequestLogProvider>
      <SessionProvider>
        <BookingForm initialFlight="UA1" />
      </SessionProvider>
    </RequestLogProvider>,
  );
}

async function press(name: string) {
  await act(async () => fireEvent.click(screen.getByRole("button", { name })));
}

/** The newest result card's heading line: action, status, booking and badge. */
function newest() {
  return within(screen.getAllByTestId("booking-outcome")[0]!).getByRole("banner").textContent;
}

describe("BookingForm", () => {
  it("compares a replay with the booking its own key made, for any key", async () => {
    fakeApi();
    renderForm();
    fireEvent.change(screen.getByLabelText("Idempotency key"), { target: { value: "constructor" } });

    // No Book on this key yet, so there is nothing to compare a replay with.
    await press("Replay the same key");
    expect(newest()).toBe("Replay on the same key201booking #1");

    await press("Book");
    await press("Replay the same key");
    expect(newest()).toBe("Replay on the same key201booking #1same booking as the first Book");

    await press("New idempotency key");
    await press("Book");
    await press("Replay the same key");
    expect(newest()).toBe("Replay on the same key201booking #2same booking as the first Book");

    // Back to the first key: its replay is compared with booking #1, the
    // first Book on this key, not with the newest Book on any key.
    fireEvent.change(screen.getByLabelText("Idempotency key"), { target: { value: "constructor" } });
    await press("Replay the same key");
    expect(newest()).toBe("Replay on the same key201booking #1same booking as the first Book");
  });

  it("empties the status line while a request is out, so the same answer is announced again", async () => {
    const api = fakeApi();
    renderForm();
    const status = screen.getByRole("status");

    await press("Book");
    expect(status.textContent).toBe("Book: 201, booking #1");

    const release = api.hold();
    await press("Replay the same key");
    expect(status.textContent).toBe("");
    expect(screen.getByRole("button", { name: "Replay the same key" }).getAttribute("aria-busy")).toBe("true");
    expect(screen.getByRole("button", { name: "Book" })).toHaveProperty("disabled", true);

    await act(async () => release());
    expect(status.textContent).toBe("Replay on the same key: 201, booking #1");
  });

  it("shows a refusal once, in the status line and a silent banner", async () => {
    fakeApi();
    renderForm();
    await press("Book");
    await press("Same key, different body");
    expect(screen.getByRole("status").textContent).toBe("Same key, different body: 409, IDEMPOTENCY_KEY_REUSED");
    expect(screen.queryByRole("alert")).toBeNull();
    expect(document.querySelector("[data-code=IDEMPOTENCY_KEY_REUSED]")).not.toBeNull();
  });

  it("reads the seats again only after a booking that got an answer", async () => {
    let seats = 100;
    let timeOut = true;
    const reads = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn(async (_input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method !== "POST") {
          reads();
          return Response.json({ flightNumber: "UA1", availableSeats: seats });
        }
        if (timeOut) {
          return Response.json({ code: "CONSOLE_UPSTREAM_TIMEOUT", message: "No answer in 15 s" }, { status: 504 });
        }
        seats -= 1;
        return Response.json(
          { bookingId: 1, flightNumber: "UA1", passengerName: "Test Passenger", seats: 1, createdAt: "2026-09-26T10:00:00Z", cancelledAt: null },
          { status: 201, headers: { Location: "/api/v1/bookings/1" } },
        );
      }),
    );
    renderForm();

    // The API may still apply a booking the console gave up on, so a read
    // straight after it could miss a change that is still to come.
    await press("Book");
    expect(reads).toHaveBeenCalledTimes(1);
    expect(screen.getByTestId("seats-change").textContent).toBe("\u2014");

    timeOut = false;
    await press("Book");
    expect(reads).toHaveBeenCalledTimes(3);
    expect(screen.getAllByTestId("seats-change")[0]!.textContent).toBe("100 \u2192 99");
  });

  it("marks the fields from the last answer, a booking's or the race's", async () => {
    let refuse = true;
    const row = (index: number, status: number, body: unknown) => ({
      index,
      status,
      requestId: `web-${index}`,
      echoedRequestId: `web-${index}`,
      location: null,
      ms: 5,
      body,
    });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const path = new URL(String(input), "http://console.test").pathname;
        if (init?.method !== "POST") return Response.json({ flightNumber: "UA1", availableSeats: 100 });
        const refusal = { code: "VALIDATION_FAILED", message: "Invalid request", fieldErrors: { passengerName: "must not be blank" } };
        if (path === "/api/v1/bookings") return Response.json(refusal, { status: 400 });
        const body = refuse ? refusal : { bookingId: 1, flightNumber: "UA1", passengerName: "Test Passenger", seats: 1 };
        const rows = Array.from({ length: 10 }, (_, index) => row(index, refuse ? 400 : 201, body));
        return Response.json({ rows, totalMs: 12 });
      }),
    );
    renderForm();
    const name = () => screen.getByLabelText("Passenger name").getAttribute("aria-invalid");

    await press("Book");
    expect(name()).toBe("true");

    refuse = false;
    await press("Race 10 callers on this key");
    expect(name()).not.toBe("true");

    refuse = true;
    await press("Race 10 callers on this key");
    expect(name()).toBe("true");
  });

  it("says a race none of whose callers got an answer may still finish, and reads the seats once", async () => {
    const reads = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        if (init?.method !== "POST") {
          reads();
          return Response.json({ flightNumber: "UA1", availableSeats: 100 });
        }
        const body = { code: "CONSOLE_UPSTREAM_UNREACHABLE", message: "The console could not reach the API." };
        const rows = Array.from({ length: 10 }, (_, index) => ({
          index,
          status: 0,
          requestId: `web-${index}`,
          echoedRequestId: null,
          location: null,
          ms: 5,
          body,
        }));
        return Response.json({ rows, totalMs: 12 });
      }),
    );
    renderForm();

    await press("Race 10 callers on this key");

    expect(screen.getByTestId("race-unsettled").textContent).toContain("a request that reached the API may still finish");
    expect(reads).toHaveBeenCalledTimes(1);
  });
});
