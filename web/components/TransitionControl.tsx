"use client";

import { useState } from "react";
import type { ClassifiedError } from "@/lib/errors";
import { nextStates } from "@/lib/transitions";
import { FLIGHT_STATUSES, type FlightStatus } from "@/lib/types";
import { ErrorBanner } from "./ErrorBanner";
import { Button, Select } from "./ui";

// The buttons are the moves the service allows. The select sends any status,
// so the service's own refusal, a 409 ILLEGAL_STATUS_TRANSITION, is one click
// away. The page never decides for the service.
export function TransitionControl({
  status,
  onSend,
}: {
  status: FlightStatus;
  onSend: (next: FlightStatus) => Promise<ClassifiedError | null>;
}) {
  const [any, setAny] = useState<FlightStatus>("ARRIVED");
  const [error, setError] = useState<ClassifiedError | null>(null);
  const [busy, setBusy] = useState(false);
  const allowed = nextStates(status);

  async function send(next: FlightStatus) {
    setBusy(true);
    setError(await onSend(next));
    setBusy(false);
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Allowed transitions">
        {allowed.length === 0 ? (
          <span className="text-sm text-slate-500">{status} is terminal: no status can follow it.</span>
        ) : (
          allowed.map((next) => (
            <Button key={next} tone="secondary" disabled={busy} onClick={() => send(next)}>
              Move to {next}
            </Button>
          ))
        )}
      </div>
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <label htmlFor="any-status" className="text-slate-600 dark:text-slate-400">
          Send any status:
        </label>
        <div className="w-40">
          <Select id="any-status" value={any} onChange={(e) => setAny(e.target.value as FlightStatus)}>
            {FLIGHT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </Select>
        </div>
        <Button tone="ghost" disabled={busy} onClick={() => send(any)}>
          Send
        </Button>
      </div>
      <ErrorBanner error={error} />
    </div>
  );
}
