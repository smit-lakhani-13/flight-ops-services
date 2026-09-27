import type { Flight } from "@/lib/types";
import { formatInstant, MUTED, SeatBar, StatusBadge, TextLink } from "./ui";

// The page shows its own empty state, with an action, when there are no rows.
// From 768 px the table scrolls inside its card and keeps its header in view.
// The wrapper is `relative` so the seat bars' screen-reader labels, which are
// absolutely positioned, are clipped with the table instead of widening a
// phone's page, and its small side padding leaves the links' focus outline
// room inside the scroll box. Below 640 px the departure sits under the route
// and the seat bar gives way to the count, so status and seats stay on screen.
export function FlightTable({ flights }: { flights: Flight[] }) {
  const th = "sticky top-0 z-10 border-b border-slate-200 bg-white py-2 pr-4 font-medium whitespace-nowrap dark:border-slate-800 dark:bg-slate-900";
  return (
    <div className="relative -mx-1 overflow-x-auto px-1 md:max-h-[70vh] md:overflow-y-auto">
      <table className="w-full text-left text-sm" data-testid="flight-table">
        <thead className={`text-xs tracking-wide uppercase ${MUTED}`}>
          <tr>
            <th scope="col" className={th}>Flight</th>
            <th scope="col" className={th}>Route</th>
            <th scope="col" className={`${th} hidden sm:table-cell`}>
              Departs
            </th>
            <th scope="col" className={th}>Status</th>
            <th scope="col" className={`${th} pr-0`}>Seats left</th>
          </tr>
        </thead>
        <tbody>
          {flights.map((flight) => (
            <tr key={flight.flightNumber} className="border-b border-slate-100 last:border-0 dark:border-slate-800">
              <td className="py-2.5 pr-4 font-mono">
                <TextLink href={`/flights/${flight.flightNumber}`}>{flight.flightNumber}</TextLink>
              </td>
              <td className="py-2.5 pr-4">
                <span className="font-mono whitespace-nowrap">
                  {flight.origin} → {flight.destination}
                </span>
                <span className={`block text-xs sm:hidden ${MUTED}`}>{formatInstant(flight.departureTime)}</span>
              </td>
              <td className="hidden py-2.5 pr-4 whitespace-nowrap sm:table-cell">{formatInstant(flight.departureTime)}</td>
              <td className="py-2.5 pr-4">
                <StatusBadge status={flight.status} />
              </td>
              <td className="py-2.5">
                <SeatBar available={flight.availableSeats} total={flight.totalSeats} compact />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
