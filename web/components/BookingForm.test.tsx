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
 * A booking API in miniature: a new key makes a booking (201), the same key
 * and body gets that booking back (200), and a flight read answers with the
 * seats left. `hold` makes the next booking wait until it is released.
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
        { status: known ? 200 : 201, headers: { ...echo, Location: `/api/v1/bookings/${bookingId}` } },
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
    expect(newest()).toBe("Replay on the same key200booking #1same booking as the first Book");

    await press("New idempotency key");
    await press("Book");
    await press("Replay the same key");
    expect(newest()).toBe("Replay on the same key200booking #2same booking as the first Book");
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
    expect(status.textContent).toBe("Replay on the same key: 200, booking #1");
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
});
