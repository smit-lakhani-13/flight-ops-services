import {
  consoleError,
  isTimeout,
  localLocation,
  readBoundedBody,
  refuseCrossSite,
  UPSTREAM_TIMEOUT_MS,
  upstreamOrigin,
  type ForwardOptions,
} from "./proxy";
import type { RaceReport, RaceRow } from "./types";

// Ten bookings on one idempotency key, sent at once from the console's
// server. A page cannot do this itself: a browser opens at most six HTTP/1.1
// connections to one origin and queues the rest, so ten fetch() calls from a
// tab are not ten concurrent requests. It is the same experiment as act 4 of
// scripts/demo.sh (`xargs -P 10 curl`), shown on one screen.

export const RACE_SIZE = 10;

export async function runRace(request: Request, options: ForwardOptions = {}): Promise<Response> {
  const refused = refuseCrossSite(request);
  if (refused) return refused;

  let origin: string;
  try {
    origin = upstreamOrigin(options.baseUrl);
  } catch {
    return consoleError(500, "CONSOLE_MISCONFIGURED", "The console's API_BASE_URL is not a valid origin.");
  }

  const raw = await readBoundedBody(request);
  if (raw === null) {
    return consoleError(413, "CONSOLE_BODY_TOO_LARGE", "The race body is too large.");
  }
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(raw));
  } catch {
    parsed = undefined;
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return consoleError(400, "CONSOLE_BAD_REQUEST", "The race body must be one booking request as a JSON object.");
  }

  // One serialisation, so all ten requests carry byte-identical bodies. The
  // fields are not checked here: the API validates them, ten times.
  const payload = JSON.stringify(parsed);
  const authorization = request.headers.get("authorization");
  const url = `${origin}/api/v1/bookings`;
  const timeoutMs = options.timeoutMs ?? UPSTREAM_TIMEOUT_MS;
  const doFetch = options.fetch ?? fetch;

  async function one(index: number): Promise<RaceRow> {
    const requestId = `web-race-${crypto.randomUUID()}`;
    const headers = new Headers({
      "Content-Type": "application/json",
      Accept: "application/json",
      "X-Request-Id": requestId,
    });
    if (authorization !== null) headers.set("Authorization", authorization);

    const started = performance.now();
    try {
      const response = await doFetch(url, {
        method: "POST",
        headers,
        body: payload,
        cache: "no-store",
        redirect: "manual",
        signal: AbortSignal.timeout(timeoutMs),
      });
      const body = await readJson(response);
      const location = response.headers.get("location");
      return {
        index,
        status: response.status,
        requestId,
        echoedRequestId: response.headers.get("x-request-id"),
        location: location === null ? null : localLocation(location, origin),
        ms: Math.round(performance.now() - started),
        body,
      };
    } catch (error) {
      // One failed call is one row, not a failed race.
      return {
        index,
        status: 0,
        requestId,
        echoedRequestId: null,
        location: null,
        ms: Math.round(performance.now() - started),
        body: isTimeout(error)
          ? { code: "CONSOLE_UPSTREAM_TIMEOUT", message: `No answer within ${timeoutMs / 1000} s.` }
          : { code: "CONSOLE_UPSTREAM_UNREACHABLE", message: "The console could not reach the API." },
      };
    }
  }

  const started = performance.now();
  // Array.from calls one() ten times in the same tick, so every request is
  // on the wire before the first answer can arrive.
  const rows = await Promise.all(Array.from({ length: RACE_SIZE }, (_, index) => one(index)));
  const report: RaceReport = { rows, totalMs: Math.round(performance.now() - started) };
  return Response.json(report, { headers: { "Cache-Control": "no-store" } });
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.length === 0) return null;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}
