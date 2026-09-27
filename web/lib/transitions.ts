import type { FlightStatus } from "./types";

// A copy of entity/FlightStatus.java#canTransitionTo, used only to decide
// which buttons to offer; transitions.test.ts reads the Java file and fails if
// the two disagree. The service stays the authority: the console also lets you
// send any status, so you can watch it refuse one with a 409.
const NEXT: Record<FlightStatus, readonly FlightStatus[]> = {
  SCHEDULED: ["BOARDING", "DELAYED", "DEPARTED", "CANCELLED"],
  DELAYED: ["BOARDING", "DEPARTED", "CANCELLED"],
  BOARDING: ["DELAYED", "DEPARTED", "CANCELLED"],
  DEPARTED: ["ARRIVED"],
  ARRIVED: [],
  CANCELLED: [],
};

/** The statuses a flight can move to next, not counting the one it has. */
export function nextStates(status: FlightStatus): readonly FlightStatus[] {
  return NEXT[status];
}

/** Mirrors entity/FlightStatus.java#isBookable. */
export function isBookable(status: FlightStatus): boolean {
  return status === "SCHEDULED" || status === "BOARDING" || status === "DELAYED";
}

/** DELETE on a flight answers 409 once it has departed or arrived (Flight#cancel). */
export function isCancellable(status: FlightStatus): boolean {
  return status !== "DEPARTED" && status !== "ARRIVED";
}
