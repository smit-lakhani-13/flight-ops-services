"use client";

import { useState, type FormEvent, type ReactNode } from "react";
import { newId, type ApiResponse } from "@/lib/api";
import { summariseRace } from "@/lib/race-summary";
import { useApi } from "@/lib/session";
import type { Booking, BookingRequest, Flight, RaceReport } from "@/lib/types";
import { ErrorBanner } from "./ErrorBanner";
import { RaceResult } from "./RaceResult";
import {
  Button,
  Card,
  countFrom,
  Field,
  HttpStatus,
  MUTED,
  NONE,
  STATUS_EDGE,
  STATUS_TEXT,
  statusClass,
  TextInput,
  TextLink,
  type StatusClass,
} from "./ui";

type Action = "book" | "replay" | "different";

const TITLES: Record<Action, string> = {
  book: "Book",
  replay: "Replay on the same key",
  different: "Same key, different body",
};

interface Outcome {
  n: number;
  action: Action;
  sent: BookingRequest;
  result: ApiResponse<Booking>;
  before: number | null;
  after: number | null;
}

/** The line a screen reader hears when an answer arrives, in the answer's colour. */
interface Announcement {
  text: string;
  kind: StatusClass;
}

const FIELDS = ["flightNumber", "passengerName", "seats", "idempotencyKey"] as const;
const FLIGHT_NUMBER = /^\s*[A-Za-z0-9]{1,10}\s*$/;

export function BookingForm({ initialFlight }: { initialFlight: string }) {
  const api = useApi();
  const [form, setForm] = useState(() => ({
    flightNumber: initialFlight,
    passengerName: "Test Passenger",
    seats: "1",
    idempotencyKey: `web-${newId()}`,
  }));
  const [outcomes, setOutcomes] = useState<Outcome[]>([]);
  const [race, setRace] = useState<{ result: ApiResponse<RaceReport>; before: number | null; after: number | null } | null>(null);
  const [pending, setPending] = useState<Action | "race" | null>(null);
  const [announcement, setAnnouncement] = useState<Announcement | null>(null);
  // The booking each key's first successful Book made, so a replay is
  // compared with the booking made on its own key.
  const [firstByKey, setFirstByKey] = useState<Record<string, number>>({});

  const latest = outcomes[0];
  const fieldError = (name: string) => latest?.result.error?.fieldErrors[name];
  const set = (key: keyof typeof form) => (value: string) => setForm((current) => ({ ...current, [key]: value }));

  function request(): BookingRequest {
    return {
      flightNumber: form.flightNumber,
      passengerName: form.passengerName,
      seats: countFrom(form.seats),
      idempotencyKey: form.idempotencyKey,
    };
  }

  async function seatsLeft(flightNumber: string): Promise<number | null> {
    if (!FLIGHT_NUMBER.test(flightNumber)) return null;
    const flight = await api.get<Flight>(`v1/flights/${flightNumber.trim().toUpperCase()}`);
    return flight.data?.availableSeats ?? null;
  }

  async function book(action: Action) {
    setPending(action);
    try {
      const sent = request();
      if (action === "different") {
        sent.seats = Number.isInteger(sent.seats) && sent.seats < 9 ? sent.seats + 1 : 1;
      }
      const before = await seatsLeft(sent.flightNumber);
      const result = await api.post<Booking>("v1/bookings", sent);
      const after = await seatsLeft(sent.flightNumber);
      const created = result.data;
      if (action === "book" && result.ok && created) {
        setFirstByKey((current) => (sent.idempotencyKey in current ? current : { ...current, [sent.idempotencyKey]: created.bookingId }));
      }
      setOutcomes((current) => [{ n: (current[0]?.n ?? 0) + 1, action, sent, result, before, after }, ...current].slice(0, 6));
      setAnnouncement({
        text: `${TITLES[action]}: ${result.status || "no answer"}, ${created ? `booking #${created.bookingId}` : (result.error?.code ?? "no body")}`,
        kind: statusClass(result.status),
      });
    } finally {
      setPending(null);
    }
  }

  async function raceTen() {
    setPending("race");
    try {
      const sent = request();
      const before = await seatsLeft(sent.flightNumber);
      const result = await api.post<RaceReport>("race", sent);
      const after = await seatsLeft(sent.flightNumber);
      setRace({ result, before, after });
      if (result.ok && result.data) {
        const { statuses, bookingIds } = summariseRace(result.data.rows);
        const answers = Object.entries(statuses).map(([status, count]) => `${count} × ${status === "0" ? "no answer" : status}`);
        const allCreated = Object.keys(statuses).every((status) => status === "201");
        setAnnouncement({
          text: `Race: ${answers.join(", ")}, ${bookingIds.length} distinct booking${bookingIds.length === 1 ? "" : "s"}`,
          kind: allCreated ? "2xx" : "4xx",
        });
      } else {
        setAnnouncement({ text: `Race: ${result.status || "no answer"}, ${result.error?.code ?? "no body"}`, kind: statusClass(result.status) });
      }
    } finally {
      setPending(null);
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    void book("book");
  }

  const busy = pending !== null;

  return (
    <div className="flex flex-col gap-6">
      <Card title="One booking request">
        <form onSubmit={submit} noValidate aria-label="Booking" className="flex flex-col gap-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <Field label="Flight number" error={fieldError("flightNumber")}>
              <TextInput
                name="flightNumber"
                autoCapitalize="characters"
                value={form.flightNumber}
                onChange={(e) => set("flightNumber")(e.target.value)}
                invalid={!!fieldError("flightNumber")}
              />
            </Field>
            <Field label="Passenger name" error={fieldError("passengerName")}>
              <TextInput
                name="passengerName"
                value={form.passengerName}
                onChange={(e) => set("passengerName")(e.target.value)}
                invalid={!!fieldError("passengerName")}
              />
            </Field>
            <Field label="Seats" error={fieldError("seats")} hint="1 to 9">
              <TextInput name="seats" inputMode="numeric" value={form.seats} onChange={(e) => set("seats")(e.target.value)} invalid={!!fieldError("seats")} />
            </Field>
            <Field label="Idempotency key" error={fieldError("idempotencyKey")}>
              <div className="flex gap-2">
                <TextInput
                  name="idempotencyKey"
                  className="min-w-0 flex-1 font-mono"
                  autoCapitalize="none"
                  spellCheck={false}
                  value={form.idempotencyKey}
                  onChange={(e) => set("idempotencyKey")(e.target.value)}
                  invalid={!!fieldError("idempotencyKey")}
                />
                <Button tone="secondary" onClick={() => set("idempotencyKey")(`web-${newId()}`)} aria-label="New idempotency key">
                  New
                </Button>
              </div>
            </Field>
          </div>
          <div className="flex flex-col gap-2">
            <div className="flex flex-wrap gap-2">
              <Button type="submit" busy={pending === "book"} disabled={busy}>
                Book
              </Button>
              <Button tone="secondary" busy={pending === "replay"} disabled={busy} onClick={() => book("replay")}>
                Replay the same key
              </Button>
              <Button tone="secondary" busy={pending === "race"} disabled={busy} onClick={() => raceTen()}>
                Race 10 callers on this key
              </Button>
              <Button tone="secondary" busy={pending === "different"} disabled={busy} onClick={() => book("different")}>
                Same key, different body
              </Button>
            </div>
            <p role="status" className={`text-sm font-medium ${announcement ? STATUS_TEXT[announcement.kind] : ""}`}>
              {announcement?.text}
            </p>
          </div>
          <p className={`text-xs text-pretty ${MUTED}`}>
            A replay is the same request arriving twice, and gets the booking the key first made. The race sends ten
            identical requests at once from the console&apos;s server, the same experiment as act 4 of{" "}
            <code>scripts/demo.sh</code>. A different body on a used key is a client bug, and the API answers 409.
          </p>
          {latest && !latest.result.ok && <ErrorBanner error={latest.result.error} claimedFields={FIELDS} />}
        </form>
      </Card>

      {race && (
        <Card title="Race: ten callers, one key">
          {race.result.ok && race.result.data ? (
            <RaceResult report={race.result.data} before={race.before} after={race.after} />
          ) : (
            <ErrorBanner error={race.result.error} />
          )}
        </Card>
      )}

      {outcomes.length > 0 && (
        <section aria-label="Results" className="flex flex-col gap-3">
          <h2 className="text-base font-semibold">Results, newest first</h2>
          {outcomes.map((outcome) => (
            <OutcomeCard key={outcome.n} outcome={outcome} firstBooking={firstByKey[outcome.sent.idempotencyKey]} />
          ))}
        </section>
      )}
    </div>
  );
}

function OutcomeCard({ outcome, firstBooking }: { outcome: Outcome; firstBooking: number | undefined }) {
  const { result, before, after, sent } = outcome;
  const booking = result.data;
  const debited = before !== null && after !== null ? before - after : null;
  const same = booking !== null && booking !== undefined && booking.bookingId === firstBooking;
  return (
    <article
      data-testid="booking-outcome"
      data-action={outcome.action}
      className={`min-w-0 rounded-card border border-l-4 border-slate-200 bg-white p-4 text-sm shadow-card dark:border-slate-800 dark:bg-slate-900 ${STATUS_EDGE[statusClass(result.status)]}`}
    >
      <header className="mb-3 flex flex-wrap items-center gap-2">
        <h3 className="font-semibold">{TITLES[outcome.action]}</h3>
        <HttpStatus status={result.status} />
        {booking ? (
          <TextLink href={`/bookings/${booking.bookingId}`}>
            <span data-testid="booking-id">booking #{booking.bookingId}</span>
          </TextLink>
        ) : (
          <span className="font-mono font-semibold break-all">{result.error?.code}</span>
        )}
        {outcome.action === "replay" && booking && firstBooking !== undefined && (
          <span
            className={`rounded-full px-2 py-0.5 text-xs font-medium ${
              same
                ? "bg-emerald-50 text-emerald-800 dark:bg-emerald-400/10 dark:text-emerald-300"
                : "bg-amber-50 text-amber-800 dark:bg-amber-400/10 dark:text-amber-300"
            }`}
          >
            {same ? "same booking as the first Book" : "a different booking"}
          </span>
        )}
      </header>
      <dl className="grid gap-x-6 gap-y-1.5 sm:grid-cols-2">
        <Row label="Sent">
          {Number.isNaN(sent.seats) ? <code>seats: null</code> : `${sent.seats} seat${sent.seats === 1 ? "" : "s"}`} on{" "}
          {sent.flightNumber}, key <code className="break-all">{sent.idempotencyKey}</code>
        </Row>
        <Row label="Seats left">
          <span data-testid="seats-change">{before !== null && after !== null ? `${before} → ${after}` : NONE}</span>
          {debited !== null && <span className={MUTED}> ({debited} debited)</span>}
        </Row>
        <Row label="Location">{result.location ? <code className="break-all">{result.location}</code> : NONE}</Row>
        <Row label="X-Request-Id">
          <code className="break-all">{result.requestId}</code>
          {result.echoedRequestId === result.requestId && <span className={MUTED}> (echoed)</span>}
        </Row>
        {!result.ok && result.error && (
          <Row label="Message">
            <span data-testid="outcome-message">{result.error.message}</span>
          </Row>
        )}
      </dl>
    </article>
  );
}

function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex min-w-0 gap-3">
      <dt className={`w-24 shrink-0 ${MUTED}`}>{label}</dt>
      <dd className="min-w-0 wrap-anywhere">{children}</dd>
    </div>
  );
}
