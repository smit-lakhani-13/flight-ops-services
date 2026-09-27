import { isErrorBody, type ErrorBody } from "./types";

export type ErrorKind =
  | "unauthenticated"
  | "forbidden"
  | "validation"
  | "notFound"
  | "conflict"
  | "unavailable"
  | "server"
  | "console"
  | "network"
  | "rejected";

export interface ClassifiedError {
  kind: ErrorKind;
  status: number;
  code: string;
  message: string;
  fieldErrors: Record<string, string>;
  /** Seconds, from `Retry-After`, on a 503. */
  retryAfter: number | null;
}

// The console never keeps its own table of the service's error codes: it shows
// whatever code and message came back, and only sorts them into kinds so the
// page can say what to do next.
const CONSOLE_CODES = new Set([
  "CONSOLE_UPSTREAM_UNREACHABLE",
  "CONSOLE_UPSTREAM_TIMEOUT",
  "CONSOLE_PATH_REFUSED",
  "CONSOLE_CROSS_SITE_REFUSED",
  "CONSOLE_BODY_TOO_LARGE",
  "CONSOLE_BAD_REQUEST",
  "CONSOLE_METHOD_REFUSED",
  "CONSOLE_MISCONFIGURED",
]);

export function classify(
  status: number,
  body: unknown,
  retryAfterHeader: string | null = null,
): ClassifiedError {
  const envelope: ErrorBody | null = isErrorBody(body) ? body : null;
  const code = envelope?.code ?? `HTTP_${status}`;
  const fieldErrors = envelope?.fieldErrors ?? {};
  const message =
    envelope?.message ??
    (Object.keys(fieldErrors).length > 0
      ? "Some fields were refused."
      : defaultMessage(status));

  return {
    kind: kindOf(status, code),
    status,
    code,
    message,
    fieldErrors,
    retryAfter: parseRetryAfter(retryAfterHeader),
  };
}

function kindOf(status: number, code: string): ErrorKind {
  if (status === 0) return "network";
  if (CONSOLE_CODES.has(code)) return "console";
  if (status === 401) return "unauthenticated";
  if (status === 403) return "forbidden";
  if (status === 404) return "notFound";
  if (status === 409) return "conflict";
  if (status === 503) return "unavailable";
  if (status === 400 && code === "VALIDATION_FAILED") return "validation";
  if (status >= 500) return "server";
  return "rejected";
}

function defaultMessage(status: number): string {
  if (status === 0) return "The console's server did not answer.";
  if (status === 404) return "Nothing was found at that address.";
  if (status >= 500) return "The server failed to answer the request.";
  return "The request was refused.";
}

function parseRetryAfter(header: string | null): number | null {
  if (header === null || !/^\d+$/.test(header.trim())) return null;
  return Number.parseInt(header.trim(), 10);
}

export function hint(error: ClassifiedError): string | null {
  switch (error.kind) {
    case "unauthenticated":
      return "The credentials were not accepted. Sign in again.";
    case "forbidden":
      return "You are signed in, but this account lacks the authority for this call. The API and the actuator use different accounts.";
    case "unavailable":
      return error.retryAfter !== null
        ? `The request was valid and the service was busy. Retry after ${error.retryAfter} s.`
        : "The service is unavailable. Retry shortly.";
    case "console":
      return "The console's own server answered this, not the API.";
    case "network":
      return "Check that the console's server is still running.";
    default:
      return null;
  }
}
