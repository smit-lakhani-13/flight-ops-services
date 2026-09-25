"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { BookingTable } from "@/components/BookingTable";
import { ErrorBanner } from "@/components/ErrorBanner";
import { RefreshIcon } from "@/components/icons";
import { Pager } from "@/components/Pager";
import { RequireSession } from "@/components/RequireSession";
import { TransitionControl } from "@/components/TransitionControl";
import { Button, Card, formatInstant, KeyValue, MUTED, PageTitle, SeatBar, Skeleton, StatusBadge, TextLink } from "@/components/ui";
import type { ClassifiedError } from "@/lib/errors";
import { useApi } from "@/lib/session";
import { isBookable, isCancellable } from "@/lib/transitions";
import type { Booking, Flight, FlightStatus, Page } from "@/lib/types";
import { useResource } from "@/lib/use-resource";

export default function FlightPage() {
  const { flightNumber } = useParams<{ flightNumber: string }>();
  return (
    <>
      <PageTitle
        title={<span className="font-mono">{flightNumber}</span>}
        documentTitle={flightNumber}
        subtitle={
          <TextLink href="/flights" standalone>
            All flights
          </TextLink>
        }
      />
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
  const [cancelling, setCancelling] = useState(false);
  const [cancelError, setCancelError] = useState<ClassifiedError | null>(null);
  // Opening or closing the question removes the button that was pressed, so
  // the focus goes to Keep it, the safe answer, and back to Cancel flight.
  const keep = useRef<HTMLButtonElement>(null);
  const cancelFlight = useRef<HTMLButtonElement>(null);
  const questionUsed = useRef(false);
  useEffect(() => {
    if (!questionUsed.current) return;
    (confirming ? keep : cancelFlight).current?.focus();
  }, [confirming]);

  function ask(open: boolean) {
    questionUsed.current = true;
    setConfirming(open);
  }

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

  // The question stays up, its answer busy, until the DELETE returns.
  async function cancel() {
    setCancelling(true);
    try {
      const result = await api.del(`v1/flights/${encodeURIComponent(flightNumber)}`);
      setCancelError(result.ok ? null : result.error);
      reloadFlight();
    } finally {
      setCancelling(false);
      ask(false);
    }
  }

  if (flight === null) {
    return (
      <Card>
        <Skeleton rows={3} label="Loading the flight" />
      </Card>
    );
  }
  if (!flight.ok || !flight.data) return <ErrorBanner error={flight.error} />;
  const f = flight.data;

  return (
    <div className="flex flex-col gap-6">
      <Card
        title={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <span className="font-mono">
              {f.origin} → {f.destination}
            </span>
            <StatusBadge status={f.status} />
          </span>
        }
        actions={
          <TextLink href={`/book?flight=${f.flightNumber}`} standalone>
            Book a seat
          </TextLink>
        }
      >
        <KeyValue
          items={[
            { label: "Departs", value: formatInstant(f.departureTime), note: f.departureTime },
            { label: "Seats left", value: <SeatBar available={f.availableSeats} total={f.totalSeats} />, testId: "seats-left" },
            { label: "Sells seats", value: isBookable(f.status) ? "Yes" : "No: a booking gets 409 FLIGHT_NOT_BOOKABLE" },
          ]}
        />
      </Card>

      <Card title="Status">
        <TransitionControl status={f.status} onSend={transition} />
      </Card>

      <Card title="Cancel the flight">
        <p className={`mb-4 text-sm text-pretty ${MUTED}`}>
          DELETE marks the flight <code>CANCELLED</code> and keeps its row and its bookings. Repeating it answers 204
          again. A flight that has departed or arrived answers 409.
        </p>
        {confirming ? (
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium">Cancel {f.flightNumber}? It cannot be undone.</span>
            <Button tone="danger" busy={cancelling} onClick={cancel}>
              Yes, cancel it
            </Button>
            <Button ref={keep} tone="secondary" disabled={cancelling} onClick={() => ask(false)}>
              Keep it
            </Button>
          </div>
        ) : (
          <div className="flex flex-wrap items-center gap-3">
            <Button ref={cancelFlight} tone="danger" onClick={() => ask(true)}>
              Cancel flight
            </Button>
            {!isCancellable(f.status) && (
              <span className={`text-xs ${MUTED}`}>The service will refuse this one: {f.status} cannot be cancelled.</span>
            )}
          </div>
        )}
        <div className="mt-3">
          <ErrorBanner error={cancelError} />
        </div>
      </Card>

      <Card
        title="Bookings on this flight"
        actions={
          <Button tone="ghost" icon={<RefreshIcon />} onClick={reloadBookings}>
            Refresh
          </Button>
        }
      >
        {bookings === null ? (
          <Skeleton rows={2} label="Loading the bookings" />
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
