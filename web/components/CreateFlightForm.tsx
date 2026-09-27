"use client";

import { useRouter } from "next/navigation";
import { useState, useTransition, type FormEvent } from "react";
import type { ClassifiedError } from "@/lib/errors";
import { useApi } from "@/lib/session";
import type { CreateFlightRequest, Flight } from "@/lib/types";
import { ErrorBanner } from "./ErrorBanner";
import { Button, countFrom, Field, pageFor, TextInput } from "./ui";

const FIELDS = ["flightNumber", "origin", "destination", "totalSeats", "departureTime"] as const;

function inAWeek(): string {
  const date = new Date(Date.now() + 7 * 24 * 3600 * 1000);
  date.setMinutes(0, 0, 0);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:00`;
}

// noValidate on purpose: the browser's own checks would stop a bad value
// before the API could refuse it, and the API's per-field answer is what this
// form exists to show.
export function CreateFlightForm() {
  const api = useApi();
  const router = useRouter();
  const [form, setForm] = useState(() => ({ flightNumber: "", origin: "", destination: "", totalSeats: "180", departure: inAWeek() }));
  const [error, setError] = useState<ClassifiedError | null>(null);
  const [busy, setBusy] = useState(false);
  // The move to the new flight's page is a transition, so the button stays
  // busy until that page is ready and never outlives it.
  const [leaving, startLeaving] = useTransition();

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const departure = new Date(form.departure);
      const body: CreateFlightRequest = {
        flightNumber: form.flightNumber,
        origin: form.origin,
        destination: form.destination,
        totalSeats: countFrom(form.totalSeats),
        departureTime: Number.isNaN(departure.getTime()) ? form.departure : departure.toISOString(),
      };
      const result = await api.post<Flight>("v1/flights", body);
      if (!result.ok) {
        setError(result.error);
        return;
      }
      const next = pageFor(result.location);
      if (next) startLeaving(() => router.push(next));
    } finally {
      setBusy(false);
    }
  }

  const fieldError = (name: string) => error?.fieldErrors[name];
  const set = (key: keyof typeof form) => (value: string) => setForm((current) => ({ ...current, [key]: value }));

  return (
    <form onSubmit={submit} noValidate aria-label="Create flight" className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-5">
      <Field label="Flight number" error={fieldError("flightNumber")} hint="Letters and digits; stored upper-case">
        <TextInput name="flightNumber" autoCapitalize="characters" value={form.flightNumber} onChange={(e) => set("flightNumber")(e.target.value)} invalid={!!fieldError("flightNumber")} />
      </Field>
      <Field label="Origin" error={fieldError("origin")} hint="Three letters">
        <TextInput name="origin" autoCapitalize="characters" value={form.origin} onChange={(e) => set("origin")(e.target.value)} invalid={!!fieldError("origin")} />
      </Field>
      <Field label="Destination" error={fieldError("destination")} hint="Three letters">
        <TextInput
          name="destination"
          autoCapitalize="characters"
          value={form.destination}
          onChange={(e) => set("destination")(e.target.value)}
          invalid={!!fieldError("destination")}
        />
      </Field>
      <Field label="Seats" error={fieldError("totalSeats")} hint="1 to 850">
        <TextInput
          name="totalSeats"
          inputMode="numeric"
          value={form.totalSeats}
          onChange={(e) => set("totalSeats")(e.target.value)}
          invalid={!!fieldError("totalSeats")}
        />
      </Field>
      <Field label="Departs (your time zone)" error={fieldError("departureTime")} hint="Sent as a UTC instant">
        <TextInput
          name="departure"
          type="datetime-local"
          value={form.departure}
          onChange={(e) => set("departure")(e.target.value)}
          invalid={!!fieldError("departureTime")}
        />
      </Field>
      <div className="flex flex-col gap-3 sm:col-span-2 lg:col-span-3 xl:col-span-5">
        <ErrorBanner error={error} claimedFields={FIELDS} />
        <div>
          <Button type="submit" busy={busy || leaving}>
            Create flight
          </Button>
        </div>
      </div>
    </form>
  );
}
