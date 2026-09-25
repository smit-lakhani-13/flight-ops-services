"use client";

import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { RequireSession } from "@/components/RequireSession";
import { Button, Card, formatInstant, KeyValue, MUTED, NONE, PageTitle, Skeleton, TextLink } from "@/components/ui";
import type { ClassifiedError } from "@/lib/errors";
import { useApi } from "@/lib/session";
import type { Booking } from "@/lib/types";
import { useResource } from "@/lib/use-resource";

export default function BookingPage() {
  const { bookingId } = useParams<{ bookingId: string }>();
  return (
    <>
      <PageTitle
        title={<span className="font-mono">Booking #{bookingId}</span>}
        documentTitle={`Booking #${bookingId}`}
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

  const load = useCallback(() => api.get<Booking>(`v1/bookings/${encodeURIComponent(bookingId)}`), [api, bookingId]);
  const { value: booking, set: setBooking } = useResource(load);

  async function cancel() {
    setCancelling(true);
    try {
      const result = await api.del<Booking>(`v1/bookings/${encodeURIComponent(bookingId)}`);
      if (result.ok && result.data) {
        setBooking(result);
        setCancelError(null);
        setCancels((current) => [...current, result.data?.cancelledAt ?? NONE]);
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
  if (!booking.ok || !booking.data) return <ErrorBanner error={booking.error} />;
  const b = booking.data;

  return (
    <div className="flex flex-col gap-6">
      <Card title="Booking">
        <KeyValue
          items={[
            {
              label: "Flight",
              value: (
                <TextLink href={`/flights/${b.flightNumber}`} className="font-mono">
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
          same <code>cancelledAt</code> and returns nothing twice.
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
