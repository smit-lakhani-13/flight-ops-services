"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useId, useRef, useState, type MouseEvent } from "react";
import { BookingTable } from "@/components/BookingTable";
import { ErrorBanner } from "@/components/ErrorBanner";
import { RefreshIcon } from "@/components/icons";
import { Pager } from "@/components/Pager";
import { RequireSession } from "@/components/RequireSession";
import { TransitionControl } from "@/components/TransitionControl";
import {
  Button,
  Card,
  focusIsInOrLost,
  focusPageTitle,
  formatInstant,
  KeyValue,
  MUTED,
  PageTitle,
  SeatBar,
  Skeleton,
  StatusBadge,
  TextLink,
} from "@/components/ui";
import type { ClassifiedError } from "@/lib/errors";
import { useApi } from "@/lib/session";
import { isBookable, isCancellable } from "@/lib/transitions";
import type { Booking, Flight, FlightStatus, Page } from "@/lib/types";
import { segment, shortened } from "@/lib/titles";
import { useResource } from "@/lib/use-resource";

export default function FlightPage() {
  // Decoded once, so the heading, the tab and the calls name the flight the
  // server layout titled.
  const flightNumber = segment(useParams<{ flightNumber: string }>().flightNumber);
  const shown = shortened(flightNumber);
  return (
    <>
      <PageTitle
        title={
          <span className="font-mono" title={shown === flightNumber ? undefined : flightNumber}>
            {shown}
          </span>
        }
        documentTitle={shown}
        subtitle={
          <TextLink href="/flights" standalone>
            All flights
          </TextLink>
        }
      />
      <RequireSession>
        {/* Keyed, so moving to another flight starts from a clean page. */}
        <FlightDetail key={flightNumber} flightNumber={flightNumber} />
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
  // The code a failed cancel showed, kept while the read it started is the last
  // one sent. A read error with the same code is not announced a second time.
  const [mutedCode, setMutedCode] = useState<string | null>(null);
  // What the last accepted change did, for a screen reader. It is emptied
  // before each change, so the same sentence twice is still announced.
  const [said, setSaid] = useState("");
  const questionId = useId();
  // Opening the question disables Cancel flight, so the focus goes to Keep
  // it, the safe answer; closing it hands the focus back to Cancel flight.
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
  const { value: flight, pending: flightPending, reload: reloadFlight, set: setFlight } = useResource(loadFlight);
  const { value: bookings, pending: bookingsPending, reload: reloadBookings } = useResource(loadBookings);

  // The last flight the service returned. A read that fails after it keeps
  // it on screen under the error, so the page and the focus stay put.
  const [lastGood, setLastGood] = useState<Flight | null>(null);
  const answered = flight?.ok && flight.data ? flight.data : null;
  if (answered !== null && answered !== lastGood) setLastGood(answered);
  const readError = flight !== null && answered === null ? flight.error : null;

  // Try again goes away once the flight arrives, so the focus goes to the
  // page's heading rather than falling back to the page. It reads the
  // bookings again too: an outage that failed the flight failed them as well.
  const retried = useRef(false);
  useEffect(() => {
    if (!retried.current || flightPending || readError !== null) return;
    retried.current = false;
    if (focusIsInOrLost(null)) focusPageTitle();
  }, [flightPending, readError]);
  function retry() {
    retried.current = true;
    setMutedCode(null);
    reloadFlight();
    reloadBookings();
  }

  async function transition(next: FlightStatus): Promise<ClassifiedError | null> {
    setSaid("");
    const result = await api.patch<Flight>(`v1/flights/${encodeURIComponent(flightNumber)}/status`, { status: next });
    if (result.ok && result.data) {
      setFlight(result);
      setSaid(`${flightNumber} is now ${result.data.status}`);
      return null;
    }
    return result.error;
  }

  // The question stays up, its answer busy, until the DELETE returns. The
  // second click of a double click is dropped, as on the status buttons.
  async function cancel(event: MouseEvent<HTMLButtonElement>) {
    if (event.detail > 1) return;
    setCancelling(true);
    setCancelError(null);
    setSaid("");
    try {
      const result = await api.del(`v1/flights/${encodeURIComponent(flightNumber)}`);
      if (result.ok) setSaid(`${flightNumber} cancelled`);
      else setCancelError(result.error);
      setMutedCode(result.error?.code ?? null);
      reloadFlight();
    } finally {
      setCancelling(false);
      ask(false);
    }
  }

  const tryAgain = (
    <Button tone="secondary" icon={<RefreshIcon />} busy={flightPending} onClick={retry}>
      Try again
    </Button>
  );

  if (flight === null) {
    return (
      <Card>
        <Skeleton rows={3} label="Loading the flight" />
      </Card>
    );
  }
  const f = answered ?? lastGood;
  if (f === null) {
    return (
      <Card>
        {!flightPending && <ErrorBanner error={readError} />}
        <div className="mt-3">{tryAgain}</div>
      </Card>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <p role="status" className="sr-only">
        {said}
      </p>
      {/* Try again stays while its read is out, so it keeps the focus. */}
      {readError && (
        <div className="flex flex-col items-start gap-3">
          {/* The read a failed cancel starts has been explained by the
              cancel's own alert, unless it failed another way. */}
          {!flightPending && <ErrorBanner error={readError} announce={readError.code !== mutedCode} />}
          <p className={`text-sm ${MUTED}`}>The details below are from the last answer that arrived.</p>
          {tryAgain}
        </div>
      )}
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
        {/* Cancel flight stays where it is, disabled, while the question is
            up, and the answers sit below it: a second click or tap on it can
            never land on an answer, at any width. */}
        <div className="flex flex-wrap items-center gap-3">
          <Button ref={cancelFlight} tone="danger" disabled={confirming} onClick={() => ask(true)}>
            Cancel flight
          </Button>
          {!isCancellable(f.status) && (
            <span className={`text-xs ${MUTED}`}>The service will refuse this one: {f.status} cannot be cancelled.</span>
          )}
        </div>
        {confirming && (
          <div role="group" aria-labelledby={questionId} className="mt-3 flex flex-wrap items-center gap-2">
            <span id={questionId} className="text-sm font-medium">
              Cancel {f.flightNumber}? It cannot be undone.
            </span>
            <Button ref={keep} tone="secondary" disabled={cancelling} onClick={() => ask(false)}>
              Keep it
            </Button>
            <Button tone="danger" busy={cancelling} onClick={cancel}>
              Yes, cancel it
            </Button>
          </div>
        )}
        <div className="mt-3">
          <ErrorBanner error={cancelError} />
        </div>
      </Card>

      <Card
        title="Bookings on this flight"
        actions={
          <Button tone="ghost" icon={<RefreshIcon />} busy={bookingsPending} onClick={reloadBookings}>
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
          // When the flight's own read failed too, its banner has said so.
          <ErrorBanner error={bookings.error} announce={readError === null} />
        )}
      </Card>
    </div>
  );
}
