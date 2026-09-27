"use client";

import { useSearchParams } from "next/navigation";
import { Suspense } from "react";
import { BookingForm } from "@/components/BookingForm";
import { RequireSession } from "@/components/RequireSession";
import { PageTitle } from "@/components/ui";

export default function BookPage() {
  return (
    <>
      <PageTitle
        title="Book"
        subtitle="POST /api/v1/bookings with an idempotency key. The service fingerprints the flight, the passenger and the seat count, and stores the fingerprint against the key."
      />
      <RequireSession>
        <Suspense>
          <BookWithFlight />
        </Suspense>
      </RequireSession>
    </>
  );
}

function BookWithFlight() {
  const flight = useSearchParams().get("flight") ?? "UA123";
  return <BookingForm key={flight} initialFlight={flight} />;
}
