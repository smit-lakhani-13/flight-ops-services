import type { Booking } from "@/lib/types";
import { formatInstant, MUTED, NONE, TextLink } from "./ui";

export function BookingTable({ bookings }: { bookings: Booking[] }) {
  if (bookings.length === 0) {
    return <p className={`py-4 text-sm ${MUTED}`}>No bookings yet.</p>;
  }
  return (
    <div className="relative overflow-x-auto">
      <table className="w-full text-left text-sm" data-testid="booking-table">
        <thead className={`border-b border-slate-200 text-xs tracking-wide uppercase dark:border-slate-800 ${MUTED}`}>
          <tr>
            <th scope="col" className="py-2 pr-4 font-medium">Booking</th>
            <th scope="col" className="py-2 pr-4 font-medium">Passenger</th>
            <th scope="col" className="py-2 pr-4 font-medium">Seats</th>
            <th scope="col" className="py-2 pr-4 font-medium">Created</th>
            <th scope="col" className="py-2 font-medium">Cancelled</th>
          </tr>
        </thead>
        <tbody>
          {bookings.map((booking) => (
            <tr key={booking.bookingId} className="border-b border-slate-100 last:border-0 dark:border-slate-800">
              <td className="py-2.5 pr-4 font-mono">
                <TextLink href={`/bookings/${booking.bookingId}`}>#{booking.bookingId}</TextLink>
              </td>
              <td className="max-w-48 truncate py-2.5 pr-4" title={booking.passengerName}>
                {booking.passengerName}
              </td>
              <td className="py-2.5 pr-4 tabular-nums">{booking.seats}</td>
              <td className="py-2.5 pr-4 whitespace-nowrap">{formatInstant(booking.createdAt)}</td>
              <td className="py-2.5 whitespace-nowrap">{booking.cancelledAt ? formatInstant(booking.cancelledAt) : NONE}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
