"use client";

import { useRef, useState } from "react";
import type { ClassifiedError } from "@/lib/errors";
import { nextStates } from "@/lib/transitions";
import { FLIGHT_STATUSES, type FlightStatus } from "@/lib/types";
import { ErrorBanner } from "./ErrorBanner";
import { Button, FOCUS, focusIsInOrLost, Select } from "./ui";

/** The pressed button when it is Send; a move is named by its status. */
const SEND = "send";

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
  // The button waiting on an answer shows busy and keeps the focus; the
  // others are disabled until the answer comes.
  const [pressed, setPressed] = useState<string | null>(null);
  const moves = useRef<HTMLDivElement>(null);
  const allowed = nextStates(status);

  // A message about the last attempt goes when the status moves, however it
  // moved. React's pattern for adjusting state to a prop, in place of a key
  // that would remount the control and drop the focus with it.
  const [errorFor, setErrorFor] = useState(status);
  if (errorFor !== status) {
    setErrorFor(status);
    setError(null);
  }

  async function send(next: FlightStatus, button: string) {
    setPressed(button);
    try {
      const refused = await onSend(next);
      setError(refused);
      // A move the service accepts takes its own button away, so the focus
      // goes to the moves that follow. Not to the first of them: a held
      // Enter would press it too.
      if (!refused && button !== SEND && focusIsInOrLost(moves.current)) moves.current?.focus();
    } catch (error) {
      // onSend answers with an error value; a throw is a bug, so it is logged
      // and the buttons come back rather than staying disabled.
      console.error("A status change failed", error);
    } finally {
      setPressed(null);
    }
  }

  return (
    <div className="flex flex-col gap-3">
      <div
        ref={moves}
        tabIndex={-1}
        className={`flex flex-wrap items-center gap-2 rounded-control ${FOCUS}`}
        role="group"
        aria-label="Allowed transitions"
      >
        {allowed.length === 0 ? (
          <span className="text-sm text-slate-600 dark:text-slate-400">{status} is terminal: no status can follow it.</span>
        ) : (
          allowed.map((next) => (
            <Button
              key={next}
              tone="secondary"
              busy={pressed === next}
              disabled={pressed !== null}
              onClick={() => send(next, next)}
            >
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
        <Button tone="secondary" busy={pressed === SEND} disabled={pressed !== null} onClick={() => send(any, SEND)}>
          Send
        </Button>
      </div>
      <ErrorBanner error={error} />
    </div>
  );
}
