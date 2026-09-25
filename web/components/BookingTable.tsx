import type { Booking } from "@/lib/types";
import { formatInstant, MUTED, NONE, TextLink } from "./ui";

// Below 640 px the dates give way, so the id, the passenger and the seats stay
// on screen, and a cancelled booking says so beside its id. The wrapper's
// small side padding leaves the links' focus outline room in the scroll box.
export function BookingTable({ bookings }: { bookings: Booking[] }) {
  if (bookings.length === 0) {
    return <p className={`py-4 text-sm ${MUTED}`}>No bookings yet.</p>;
  }
  return (
    <div className="relative -mx-1 overflow-x-auto px-1">
      <table className="w-full text-left text-sm" data-testid="booking-table">
        <thead className={`border-b border-slate-200 text-xs tracking-wide uppercase dark:border-slate-800 ${MUTED}`}>
          <tr>
            <th scope="col" className="py-2 pr-4 font-medium">Booking</th>
            <th scope="col" className="py-2 pr-4 font-medium">Passenger</th>
            <th scope="col" className="py-2 pr-4 font-medium">Seats</th>
            <th scope="col" className="hidden py-2 pr-4 font-medium sm:table-cell">
              Created
            </th>
            <th scope="col" className="hidden py-2 font-medium sm:table-cell">
              Cancelled
            </th>
          </tr>
        </thead>
        <tbody>
          {bookings.map((booking) => (
            <tr key={booking.bookingId} className="border-b border-slate-100 last:border-0 dark:border-slate-800">
              <td className="py-2.5 pr-4 font-mono whitespace-nowrap">
                <TextLink href={`/bookings/${booking.bookingId}`}>#{booking.bookingId}</TextLink>
                {booking.cancelledAt && (
                  <span className="ml-2 rounded-full bg-rose-50 px-1.5 py-0.5 font-sans text-xs font-medium text-rose-800 sm:hidden dark:bg-rose-400/10 dark:text-rose-300">
                    cancelled
                  </span>
                )}
              </td>
              <td className="max-w-48 truncate py-2.5 pr-4" title={booking.passengerName}>
                {booking.passengerName}
              </td>
              <td className="py-2.5 pr-4 tabular-nums">{booking.seats}</td>
              <td className="hidden py-2.5 pr-4 whitespace-nowrap sm:table-cell">{formatInstant(booking.createdAt)}</td>
              <td className="hidden py-2.5 whitespace-nowrap sm:table-cell">
                {booking.cancelledAt ? formatInstant(booking.cancelledAt) : NONE}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
