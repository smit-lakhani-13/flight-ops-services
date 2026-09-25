"use client";

import { useState } from "react";
import { HealthCard } from "@/components/HealthCard";
import { Meter } from "@/components/Meter";
import { RequireSession } from "@/components/RequireSession";
import { Button, PageTitle } from "@/components/ui";

// The meters doc/OPERATIONS.md#metrics describes, read through the actuator.
const METERS = [
  { name: "bookings.booked", description: "Booking requests: created debited seats, replayed did not." },
  { name: "bookings.cancelled", description: "Cancellations: cancelled released seats, already_cancelled was a no-op." },
  { name: "bookings.lock_timeout", description: "Requests that gave up waiting for the flight row lock." },
  { name: "outbox.pending", description: "Events written and not yet published." },
  { name: "outbox.dead", description: "Events that ran out of publish attempts." },
  { name: "outbox.publish", description: "Publish attempts by the outbox poller, by outcome." },
  { name: "outbox.pruned", description: "Published events deleted by the pruner." },
];

export default function OpsPage() {
  const [tick, setTick] = useState(0);
  return (
    <>
      <PageTitle
        title="Ops"
        subtitle="Health needs no credentials; only the ops account sees the components behind it. The meters need ops too: the api account gets the actuator's 403."
        actions={<Button tone="secondary" onClick={() => setTick((t) => t + 1)}>Refresh</Button>}
      />
      <div className="grid gap-4 md:grid-cols-3">
        <HealthCard title="Health, no credentials" path="actuator/health" anonymous refreshKey={tick} />
        <HealthCard title="Liveness" path="actuator/health/liveness" anonymous refreshKey={tick} />
        <HealthCard title="Readiness" path="actuator/health/readiness" anonymous refreshKey={tick} />
      </div>
      <div className="mt-8">
        <RequireSession>
          <div className="flex flex-col gap-4">
            <HealthCard title="Health, with your credentials" path="actuator/health" anonymous={false} refreshKey={tick} />
            <h2 className="text-base font-semibold">Meters</h2>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {METERS.map((m) => (
                <Meter key={m.name} name={m.name} description={m.description} refreshKey={tick} />
              ))}
            </div>
          </div>
        </RequireSession>
      </div>
    </>
  );
}
