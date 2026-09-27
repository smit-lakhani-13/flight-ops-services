// The service's JSON shapes, as FlightDto, BookingDto and the two error
// envelopes write them. doc/api.md is the contract; these types only describe it.

export const FLIGHT_STATUSES = [
  "SCHEDULED",
  "BOARDING",
  "DELAYED",
  "DEPARTED",
  "ARRIVED",
  "CANCELLED",
] as const;

export type FlightStatus = (typeof FLIGHT_STATUSES)[number];

export interface Flight {
  flightNumber: string;
  origin: string;
  destination: string;
  totalSeats: number;
  availableSeats: number;
  status: FlightStatus;
  departureTime: string;
}

export interface Booking {
  bookingId: number;
  flightNumber: string;
  passengerName: string;
  seats: number;
  createdAt: string;
  cancelledAt: string | null;
}

export interface Page<T> {
  content: T[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface CreateFlightRequest {
  flightNumber: string;
  origin: string;
  destination: string;
  totalSeats: number;
  departureTime: string;
}

export interface BookingRequest {
  flightNumber: string;
  passengerName: string;
  seats: number;
  idempotencyKey: string;
}

/** Either envelope: `{code, message, timestamp}` or `{code, fieldErrors, timestamp}`. */
export interface ErrorBody {
  code: string;
  message?: string;
  fieldErrors?: Record<string, string>;
  timestamp?: string;
}

export interface Health {
  status: string;
  components?: Record<string, { status: string; details?: Record<string, unknown> }>;
  groups?: string[];
}

export interface Metric {
  name: string;
  description?: string;
  baseUnit?: string;
  measurements: { statistic: string; value: number }[];
  availableTags: { tag: string; values: string[] }[];
}

/** One of the ten answers the console's race endpoint collects. */
export interface RaceRow {
  index: number;
  status: number;
  requestId: string;
  echoedRequestId: string | null;
  location: string | null;
  ms: number;
  body: unknown;
}

export interface RaceReport {
  rows: RaceRow[];
  totalMs: number;
}

export function isErrorBody(value: unknown): value is ErrorBody {
  return (
    typeof value === "object" &&
    value !== null &&
    typeof (value as { code?: unknown }).code === "string"
  );
}
