import type { RaceRow } from "./types";

/** What the page shows above the ten rows. */
export function summariseRace(rows: readonly RaceRow[]): {
  statuses: Record<string, number>;
  bookingIds: number[];
} {
  const statuses: Record<string, number> = {};
  const ids = new Set<number>();
  for (const row of rows) {
    statuses[row.status] = (statuses[row.status] ?? 0) + 1;
    const id = (row.body as { bookingId?: unknown } | null)?.bookingId;
    if (typeof id === "number") ids.add(id);
  }
  return { statuses, bookingIds: [...ids].sort((a, b) => a - b) };
}
