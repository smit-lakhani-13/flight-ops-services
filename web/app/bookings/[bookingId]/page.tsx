"use client";

import { useParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { RefreshIcon } from "@/components/icons";
import { RequireSession } from "@/components/RequireSession";
import {
  Button,
  Card,
  focusIsInOrLost,
  focusPageTitle,
  formatInstant,
  KeyValue,
  MUTED,
  NONE,
  PageTitle,
  Skeleton,
  TextLink,
} from "@/components/ui";
import type { ClassifiedError } from "@/lib/errors";
import { useApi } from "@/lib/session";
import { LONGEST_BOOKING_ID, segment, shortened } from "@/lib/titles";
import type { Booking } from "@/lib/types";
import { useResource } from "@/lib/use-resource";

export default function BookingPage() {
  // Decoded once, so the heading, the tab and the calls name the booking the
  // server layout titled.
  const bookingId = segment(useParams<{ bookingId: string }>().bookingId);
  const shown = shortened(bookingId, LONGEST_BOOKING_ID);
  return (
    <>
      <PageTitle
        title={
          <span className="font-mono" title={shown === bookingId ? undefined : bookingId}>
            Booking #{shown}
          </span>
        }
        documentTitle={`Booking #${shown}`}
        subtitle="GET and DELETE /api/v1/bookings/{id}"
      />
      <RequireSession>
        <BookingDetail bookingId={bookingId} />
      </RequireSession>
    </>
  );
}

function BookingDetail({ bookingId }: { bookingId: string }) {
  const api = useApi();
  const [cancelError, setCancelError] = useState<ClassifiedError | null>(null);
  const [cancels, setCancels] = useState<string[]>([]);
  const [cancelling, setCancelling] = useState(false);
  // What the last cancel answered, for a screen reader. It is emptied before
  // each one, so the same answer twice is still announced.
  const [said, setSaid] = useState("");

  const load = useCallback(() => api.get<Booking>(`v1/bookings/${encodeURIComponent(bookingId)}`), [api, bookingId]);
  const { value: booking, pending, reload, set: setBooking } = useResource(load);

  // Try again goes away once the booking arrives, so the focus goes to the
  // page's heading rather than falling back to the page.
  const retried = useRef(false);
  const loaded = booking?.ok === true && !!booking.data;
  useEffect(() => {
    if (!retried.current || pending || !loaded) return;
    retried.current = false;
    if (focusIsInOrLost(null)) focusPageTitle();
  }, [pending, loaded]);

  async function cancel() {
    const before = booking?.data?.cancelledAt ?? null;
    setCancelling(true);
    setCancelError(null);
    setSaid("");
    try {
      const result = await api.del<Booking>(`v1/bookings/${encodeURIComponent(bookingId)}`);
      if (result.ok && result.data) {
        const at = result.data.cancelledAt;
        setBooking(result);
        setCancels((current) => [...current, at ?? NONE]);
        setSaid(
          before !== null && at === before
            ? `Booking #${bookingId} was already cancelled at ${formatInstant(at)}; nothing changed`
            : `Booking #${bookingId} cancelled at ${at ? formatInstant(at) : NONE}`,
        );
      } else {
        setCancelError(result.error);
      }
    } finally {
      setCancelling(false);
    }
  }

  if (booking === null) {
    return (
      <Card>
        <Skeleton rows={2} label="Loading the booking" />
      </Card>
    );
  }
  if (!booking.ok || !booking.data) {
    return (
      <Card>
        {!pending && <ErrorBanner error={booking.error} />}
        <div className="mt-3">
          <Button
            tone="secondary"
            icon={<RefreshIcon />}
            busy={pending}
            onClick={() => {
              retried.current = true;
              reload();
            }}
          >
            Try again
          </Button>
        </div>
      </Card>
    );
  }
  const b = booking.data;

  return (
    <div className="flex flex-col gap-6">
      <p role="status" className="sr-only">
        {said}
      </p>
      <Card title="Booking">
        <KeyValue
          items={[
            {
              label: "Flight",
              value: (
                <TextLink href={`/flights/${b.flightNumber}`} className="font-mono" standalone>
                  {b.flightNumber}
                </TextLink>
              ),
            },
            { label: "Passenger", value: b.passengerName },
            { label: "Seats", value: b.seats },
            { label: "Created", value: formatInstant(b.createdAt), note: b.createdAt },
            {
              label: "Cancelled",
              // The attribute keeps the instant the service sent, which the
              // list of answers below repeats.
              value: b.cancelledAt ? (
                <time dateTime={b.cancelledAt} data-testid="cancelled-at">
                  {formatInstant(b.cancelledAt)}
                </time>
              ) : (
                <span data-testid="cancelled-at">{NONE}</span>
              ),
              note: b.cancelledAt,
            },
          ]}
        />
      </Card>
      <Card title="Cancel the booking">
        <p className={`mb-4 text-sm text-pretty ${MUTED}`}>
          DELETE returns the seats to the flight and answers 200 with the booking. Sending it again answers 200 with the
          same <code>cancelledAt</code> and returns nothing twice. Once the flight has departed or arrived, an active
          booking answers 409 <code>BOOKING_NOT_CANCELLABLE</code> and keeps its seats.
        </p>
        <Button tone="danger" busy={cancelling} onClick={cancel}>
          {b.cancelledAt ? "Cancel again" : "Cancel booking"}
        </Button>
        {cancels.length > 0 && (
          <ol className="mt-3 list-decimal pl-5 font-mono text-xs wrap-anywhere" data-testid="cancel-answers">
            {cancels.map((at, i) => (
              <li key={i}>cancelledAt {at}</li>
            ))}
          </ol>
        )}
        <div className="mt-3">
          <ErrorBanner error={cancelError} />
        </div>
      </Card>
    </div>
  );
}
