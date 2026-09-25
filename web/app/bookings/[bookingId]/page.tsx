"use client";

import { useParams } from "next/navigation";
import { useCallback, useState } from "react";
import { ErrorBanner } from "@/components/ErrorBanner";
import { RequireSession } from "@/components/RequireSession";
import { Button, Card, formatInstant, PageTitle, TextLink } from "@/components/ui";
import type { ClassifiedError } from "@/lib/errors";
import { useApi } from "@/lib/session";
import type { Booking } from "@/lib/types";
import { useResource } from "@/lib/use-resource";

export default function BookingPage() {
  const { bookingId } = useParams<{ bookingId: string }>();
  return (
    <>
      <PageTitle title={<span className="font-mono">Booking #{bookingId}</span>} subtitle="GET and DELETE /api/v1/bookings/{id}" />
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

  const load = useCallback(() => api.get<Booking>(`v1/bookings/${encodeURIComponent(bookingId)}`), [api, bookingId]);
  const { value: booking, set: setBooking } = useResource(load);

  async function cancel() {
    const result = await api.del<Booking>(`v1/bookings/${encodeURIComponent(bookingId)}`);
    if (result.ok && result.data) {
      setBooking(result);
      setCancelError(null);
      setCancels((current) => [...current, result.data?.cancelledAt ?? "—"]);
    } else {
      setCancelError(result.error);
    }
  }

  if (booking === null) return <p className="text-sm text-slate-500">Loading…</p>;
  if (!booking.ok || !booking.data) return <ErrorBanner error={booking.error} />;
  const b = booking.data;

  return (
    <div className="flex flex-col gap-6">
      <Card title="Booking">
        <dl className="grid gap-4 text-sm sm:grid-cols-3">
          <Item label="Flight">
            <TextLink href={`/flights/${b.flightNumber}`}>{b.flightNumber}</TextLink>
          </Item>
          <Item label="Passenger">{b.passengerName}</Item>
          <Item label="Seats">{b.seats}</Item>
          <Item label="Created">{formatInstant(b.createdAt)}</Item>
          <Item label="Cancelled">
            <span data-testid="cancelled-at">{b.cancelledAt ?? "—"}</span>
          </Item>
        </dl>
      </Card>
      <Card title="Cancel the booking">
        <p className="mb-3 text-sm text-slate-600 dark:text-slate-400">
          DELETE returns the seats to the flight and answers 200 with the booking. Sending it again answers 200 with the
          same <code>cancelledAt</code> and returns nothing twice.
        </p>
        <Button tone="danger" onClick={cancel}>
          {b.cancelledAt ? "Cancel again" : "Cancel booking"}
        </Button>
        {cancels.length > 0 && (
          <ol className="mt-3 list-decimal pl-5 font-mono text-xs" data-testid="cancel-answers">
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

function Item({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <dt className="text-xs uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-1">{children}</dd>
    </div>
  );
}
