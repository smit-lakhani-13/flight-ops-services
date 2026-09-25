"use client";

import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { BookingTable } from "@/components/BookingTable";
import { ErrorBanner } from "@/components/ErrorBanner";
import { Pager } from "@/components/Pager";
import { RequireSession } from "@/components/RequireSession";
import { TransitionControl } from "@/components/TransitionControl";
import { Button, Card, formatInstant, PageTitle, SeatBar, StatusBadge, TextLink } from "@/components/ui";
import type { ClassifiedError } from "@/lib/errors";
import { useApi } from "@/lib/session";
import { isBookable, isCancellable } from "@/lib/transitions";
import type { Booking, Flight, FlightStatus, Page } from "@/lib/types";
import { useResource } from "@/lib/use-resource";

export default function FlightPage() {
  const { flightNumber } = useParams<{ flightNumber: string }>();
  return (
    <>
      <PageTitle title={<span className="font-mono">{flightNumber}</span>} subtitle={<TextLink href="/flights">All flights</TextLink>} />
      <RequireSession>
        <FlightDetail flightNumber={flightNumber} />
      </RequireSession>
    </>
  );
}

function FlightDetail({ flightNumber }: { flightNumber: string }) {
  const api = useApi();
  const [bookingPage, setBookingPage] = useState(0);
  const [confirming, setConfirming] = useState(false);
  const [cancelError, setCancelError] = useState<ClassifiedError | null>(null);

  const loadFlight = useCallback(() => api.get<Flight>(`v1/flights/${encodeURIComponent(flightNumber)}`), [api, flightNumber]);
  const loadBookings = useCallback(
    () => api.get<Page<Booking>>("v1/bookings", { flightNumber, page: bookingPage, size: 10, sort: "createdAt,desc" }),
    [api, flightNumber, bookingPage],
  );
  const { value: flight, reload: reloadFlight, set: setFlight } = useResource(loadFlight);
  const { value: bookings, reload: reloadBookings } = useResource(loadBookings);

  async function transition(next: FlightStatus): Promise<ClassifiedError | null> {
    const result = await api.patch<Flight>(`v1/flights/${encodeURIComponent(flightNumber)}/status`, { status: next });
    if (result.ok) {
      setFlight(result);
      return null;
    }
    return result.error;
  }

  async function cancel() {
    setConfirming(false);
    const result = await api.del(`v1/flights/${encodeURIComponent(flightNumber)}`);
    setCancelError(result.ok ? null : result.error);
    reloadFlight();
  }

  if (flight === null) return <p className="text-sm text-slate-500">Loading…</p>;
  if (!flight.ok || !flight.data) return <ErrorBanner error={flight.error} />;
  const f = flight.data;

  return (
    <div className="flex flex-col gap-6">
      <Card
        title={
          <span className="flex items-center gap-3">
            <span className="font-mono">
              {f.origin} → {f.destination}
            </span>
            <StatusBadge status={f.status} />
          </span>
        }
        actions={<TextLink href={`/book?flight=${f.flightNumber}`}>Book a seat</TextLink>}
      >
        <dl className="grid gap-4 text-sm sm:grid-cols-3">
          <div>
            <dt className="text-xs uppercase tracking-wide text-slate-500">Departs</dt>
            <dd className="mt-1">{formatInstant(f.departureTime)}</dd>
            <dd className="font-mono text-xs text-slate-500">{f.departureTime}</dd>
          </div>
          <div>
            <dt className="text-xs uppercase tracking-wide text-slate-500">Seats left</dt>
            <dd className="mt-1" data-testid="seats-left">
              <SeatBar available={f.availableSeats} total={f.totalSeats} />
            </dd>
          </div>
          <div>
            <dt className="text-xs uppercase tracking-wide text-slate-500">Sells seats</dt>
            <dd className="mt-1">{isBookable(f.status) ? "Yes" : `No: a booking gets 409 FLIGHT_NOT_BOOKABLE`}</dd>
          </div>
        </dl>
      </Card>

      <Card title="Status">
        <TransitionControl status={f.status} onSend={transition} />
      </Card>

      <Card title="Cancel the flight">
        <p className="mb-3 text-sm text-slate-600 dark:text-slate-400">
          DELETE marks the flight <code>CANCELLED</code> and keeps its row and its bookings. Repeating it answers 204
          again. A flight that has departed or arrived answers 409.
        </p>
        {confirming ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm">Cancel {f.flightNumber}? It cannot be undone.</span>
            <Button tone="danger" onClick={cancel}>
              Yes, cancel it
            </Button>
            <Button tone="secondary" onClick={() => setConfirming(false)}>
              Keep it
            </Button>
          </div>
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <Button tone="danger" onClick={() => setConfirming(true)}>
              Cancel flight
            </Button>
            {!isCancellable(f.status) && <span className="text-xs text-slate-500">The service will refuse this one: {f.status} cannot be cancelled.</span>}
          </div>
        )}
        <div className="mt-3">
          <ErrorBanner error={cancelError} />
        </div>
      </Card>

      <Card title="Bookings on this flight" actions={<Button tone="ghost" onClick={reloadBookings}>Refresh</Button>}>
        {bookings === null ? (
          <p className="text-sm text-slate-500">Loading…</p>
        ) : bookings.ok && bookings.data ? (
          <>
            <BookingTable bookings={bookings.data.content} />
            {bookings.data.page.totalPages > 1 && <Pager page={bookings.data.page} onPage={setBookingPage} />}
          </>
        ) : (
          <ErrorBanner error={bookings.error} />
        )}
      </Card>
    </div>
  );
}
