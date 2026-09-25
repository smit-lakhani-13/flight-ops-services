// The console's server half. Every browser call goes to this origin, and this
// module forwards an allow-listed subset of it to the API. The browser never
// talks to the API directly, so the API needs no CORS policy and keeps the
// security rules ADR 0006 describes. ADR 0017 records the decision.
//
// Nothing here stores or logs a credential: the Authorization header the
// browser sent is copied onto the one upstream request and then dropped.

export const DEFAULT_API_BASE_URL = "http://localhost:8080";
export const UPSTREAM_TIMEOUT_MS = 15_000;
export const MAX_BODY_BYTES = 64 * 1024;

const SEGMENT = /^[A-Za-z0-9._:-]+$/;
const METRIC_NAME = /^[a-z][a-z0-9._]*$/;

const API_METHODS: ReadonlySet<string> = new Set(["GET", "HEAD", "POST", "PATCH", "DELETE"]);
const READ_METHODS: ReadonlySet<string> = new Set(["GET", "HEAD"]);
// The statuses the Fetch standard gives no body. new Response() throws when
// handed a body with one of them, even an empty body.
const NULL_BODY_STATUSES: ReadonlySet<number> = new Set([204, 205, 304]);
const ACTUATOR_PATHS: ReadonlySet<string> = new Set([
  "health",
  "health/liveness",
  "health/readiness",
  "metrics",
]);

// Everything else the browser sends, cookies, Origin and Host included, stays
// on this side.
const FORWARDED_REQUEST_HEADERS = ["authorization", "content-type", "accept", "x-request-id"];

// WWW-Authenticate is not on the list. Passing it through would make the
// browser open its own Basic login dialog on every 401, and remember what was
// typed there for the whole origin.
const PASSED_RESPONSE_HEADERS = [
  "content-type",
  "location",
  "retry-after",
  "x-request-id",
  "allow",
  "accept",
];

export interface Target {
  path: string;
  methods: ReadonlySet<string>;
}

export interface ForwardOptions {
  fetch?: typeof fetch;
  baseUrl?: string;
  timeoutMs?: number;
}

/**
 * Maps the segments after `/api/` to a path on the API, or null when the
 * console does not forward it. `v1/...` goes to `/api/v1/...`; only health,
 * liveness, readiness and metrics go to the actuator. Prometheus, the OpenAPI
 * document and Swagger UI are the API's own pages and are not proxied.
 */
export function resolveTarget(segments: readonly string[]): Target | null {
  if (segments.length < 2) return null;
  if (segments.some((s) => !SEGMENT.test(s) || s === "." || s === "..")) return null;

  const [head, ...rest] = segments;
  if (head === "v1") {
    return {
      path: `/api/${segments.map(encodeURIComponent).join("/")}`,
      methods: API_METHODS,
    };
  }
  if (head === "actuator") {
    const joined = rest.join("/");
    if (ACTUATOR_PATHS.has(joined)) {
      return { path: `/actuator/${joined}`, methods: READ_METHODS };
    }
    const [endpoint, name] = rest;
    if (rest.length === 2 && endpoint === "metrics" && name !== undefined && METRIC_NAME.test(name)) {
      return { path: `/actuator/metrics/${name}`, methods: READ_METHODS };
    }
  }
  return null;
}

/** The API's origin, from `API_BASE_URL`. A path or query in it is refused. */
export function upstreamOrigin(configured: string | undefined = process.env.API_BASE_URL): string {
  const url = new URL(configured !== undefined && configured.length > 0 ? configured : DEFAULT_API_BASE_URL);
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error("API_BASE_URL must be an http or https URL.");
  }
  if ((url.pathname !== "/" && url.pathname !== "") || url.search !== "" || url.hash !== "") {
    throw new Error("API_BASE_URL must be an origin, with no path or query.");
  }
  return url.origin;
}

export async function forward(
  request: Request,
  segments: readonly string[],
  options: ForwardOptions = {},
): Promise<Response> {
  const refused = refuseCrossSite(request);
  if (refused) return refused;

  let origin: string;
  try {
    origin = upstreamOrigin(options.baseUrl);
  } catch {
    return consoleError(500, "CONSOLE_MISCONFIGURED", "The console's API_BASE_URL is not a valid origin.");
  }

  const target = resolveTarget(segments);
  if (target === null) {
    return consoleError(
      404,
      "CONSOLE_PATH_REFUSED",
      "The console forwards the API's /api/v1 operations and the actuator's health and metrics, nothing else.",
    );
  }

  const method = request.method.toUpperCase();
  if (!target.methods.has(method)) {
    return consoleError(405, "CONSOLE_METHOD_REFUSED", `The console does not forward ${method} on this path.`, {
      Allow: [...target.methods].join(", "),
    });
  }

  const headers = new Headers();
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = request.headers.get(name);
    if (value !== null) headers.set(name, value);
  }

  // A body only where the API reads one. undici refuses a GET or HEAD with a
  // body, and a DELETE here carries none.
  let body: ArrayBuffer | undefined;
  if (method === "POST" || method === "PATCH") {
    const read = await readBoundedBody(request);
    if (read === null) {
      return consoleError(413, "CONSOLE_BODY_TOO_LARGE", `The console forwards bodies of at most ${MAX_BODY_BYTES} bytes.`);
    }
    body = read;
  }

  const url = `${origin}${target.path}${new URL(request.url).search}`;
  const timeoutMs = options.timeoutMs ?? UPSTREAM_TIMEOUT_MS;
  // The body is read here, under the same signal, rather than streamed on.
  // A stall or a reset after the headers then becomes a 504 or a 502 in the
  // envelope, where a streamed body would reach the browser cut short. The
  // API's bodies are small: a page of flights at most.
  let upstream: Response;
  let bytes: ArrayBuffer | null = null;
  try {
    upstream = await (options.fetch ?? fetch)(url, {
      method,
      headers,
      body,
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.any([request.signal, AbortSignal.timeout(timeoutMs)]),
    });
    if (hasBody(method, upstream.status)) {
      bytes = await upstream.arrayBuffer();
    } else {
      await upstream.body?.cancel();
    }
  } catch (error) {
    return upstreamFailure(error, timeoutMs);
  }

  return relay(upstream, bytes, origin);
}

function hasBody(method: string, status: number): boolean {
  return method !== "HEAD" && !NULL_BODY_STATUSES.has(status);
}

function relay(upstream: Response, bytes: ArrayBuffer | null, origin: string): Response {
  const headers = new Headers({ "Cache-Control": "no-store" });
  for (const name of PASSED_RESPONSE_HEADERS) {
    const value = upstream.headers.get(name);
    if (value === null) continue;
    if (name === "location") {
      const local = localLocation(value, origin);
      if (local !== null) headers.set(name, local);
    } else {
      headers.set(name, value);
    }
  }
  return new Response(bytes, { status: upstream.status, headers });
}

/**
 * The API answers `Location: http://api-host/api/v1/flights/UA999`. The
 * console serves the same path on its own origin, so only the path is kept.
 * A Location on any other origin is dropped.
 */
export function localLocation(value: string, origin: string): string | null {
  try {
    const url = new URL(value, origin);
    if (url.origin !== origin) return null;
    return `${url.pathname}${url.search}`;
  } catch {
    return null;
  }
}

/**
 * Defence in depth. The proxy holds no credential, so a forged cross-site
 * request would only meet the API's own 401, but there is no reason to forward
 * it at all.
 */
export function refuseCrossSite(request: Request): Response | null {
  if (request.headers.get("sec-fetch-site") === "cross-site") {
    return consoleError(403, "CONSOLE_CROSS_SITE_REFUSED", "The console accepts requests from its own pages only.");
  }
  return null;
}

/**
 * The request's body, or null when it is larger than MAX_BODY_BYTES. A
 * declared Content-Length over the limit is refused unread. Otherwise the
 * stream is counted as it arrives, so a body with no length, or a false one,
 * is cancelled one chunk past the limit instead of being read to its end.
 */
export async function readBoundedBody(request: Request): Promise<ArrayBuffer | null> {
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > MAX_BODY_BYTES) return null;
  if (request.body === null) return new ArrayBuffer(0);

  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_BODY_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }

  const body = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    body.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return body.buffer;
}

export function upstreamFailure(error: unknown, timeoutMs: number): Response {
  if (isTimeout(error)) {
    return consoleError(504, "CONSOLE_UPSTREAM_TIMEOUT", `The API did not answer within ${timeoutMs / 1000} s.`);
  }
  return consoleError(502, "CONSOLE_UPSTREAM_UNREACHABLE", "The console could not reach the API. Is the service running?");
}

export function isTimeout(error: unknown): boolean {
  return error instanceof Error && error.name === "TimeoutError";
}

/** The console's own errors use the API's `{code, message, timestamp}` envelope. */
export function consoleError(
  status: number,
  code: string,
  message: string,
  extraHeaders: Record<string, string> = {},
): Response {
  return Response.json(
    { code, message, timestamp: new Date().toISOString() },
    { status, headers: { "Cache-Control": "no-store", ...extraHeaders } },
  );
}
