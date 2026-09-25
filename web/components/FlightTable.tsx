import type { Flight } from "@/lib/types";
import { formatInstant, SeatBar, StatusBadge, TextLink } from "./ui";

export function FlightTable({ flights }: { flights: Flight[] }) {
  if (flights.length === 0) {
    return <p className="py-6 text-center text-sm text-slate-500">No flights match.</p>;
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-sm" data-testid="flight-table">
        <thead className="border-b border-slate-200 text-xs uppercase tracking-wide text-slate-500 dark:border-slate-800">
          <tr>
            <th className="py-2 pr-4 font-medium">Flight</th>
            <th className="py-2 pr-4 font-medium">Route</th>
            <th className="py-2 pr-4 font-medium">Departs</th>
            <th className="py-2 pr-4 font-medium">Status</th>
            <th className="py-2 font-medium">Seats left</th>
          </tr>
        </thead>
        <tbody>
          {flights.map((flight) => (
            <tr key={flight.flightNumber} className="border-b border-slate-100 last:border-0 dark:border-slate-800">
              <td className="py-2 pr-4 font-mono">
                <TextLink href={`/flights/${flight.flightNumber}`}>{flight.flightNumber}</TextLink>
              </td>
              <td className="py-2 pr-4 font-mono">
                {flight.origin} → {flight.destination}
              </td>
              <td className="py-2 pr-4 whitespace-nowrap">{formatInstant(flight.departureTime)}</td>
              <td className="py-2 pr-4">
                <StatusBadge status={flight.status} />
              </td>
              <td className="py-2">
                <SeatBar available={flight.availableSeats} total={flight.totalSeats} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
