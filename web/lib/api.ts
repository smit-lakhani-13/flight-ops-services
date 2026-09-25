import { classify, type ClassifiedError } from "./errors";

// The browser half of every call. It always goes to this console's own origin
// under /api/, never to the API directly; lib/proxy.ts forwards it.

export interface ApiResponse<T> {
  ok: boolean;
  status: number;
  data: T | null;
  /** The parsed body whatever the status: a health 503 still carries one. */
  body: unknown;
  error: ClassifiedError | null;
  location: string | null;
  requestId: string;
  echoedRequestId: string | null;
  ms: number;
}

export interface LogEntry {
  id: string;
  at: string;
  method: string;
  path: string;
  status: number;
  code: string | null;
  requestId: string;
  echoedRequestId: string | null;
  location: string | null;
  ms: number;
}

export type Query = Record<string, string | number | null | undefined>;

export interface RequestOptions {
  body?: unknown;
  query?: Query;
  /** The Authorization value to send; null sends none. */
  authorization: string | null;
  signal?: AbortSignal;
}

export interface Transport {
  fetch?: typeof fetch;
  onLog?: (entry: LogEntry) => void;
  now?: () => number;
}

/**
 * A v4 UUID. crypto.randomUUID() exists only in a secure context, and a
 * console opened over plain http on a LAN address is not one.
 */
export function newId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40;
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function buildPath(path: string, query?: Query): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query ?? {})) {
    if (value === null || value === undefined || value === "") continue;
    params.append(key, String(value));
  }
  const search = params.toString();
  return `/api/${path}${search.length > 0 ? `?${search}` : ""}`;
}

export async function apiRequest<T>(
  method: string,
  path: string,
  options: RequestOptions,
  transport: Transport = {},
): Promise<ApiResponse<T>> {
  const now = transport.now ?? (() => performance.now());
  const url = buildPath(path, options.query);
  const requestId = `web-${newId()}`;
  const headers = new Headers({ Accept: "application/json", "X-Request-Id": requestId });
  if (options.authorization !== null) headers.set("Authorization", options.authorization);
  const hasBody = options.body !== undefined;
  if (hasBody) headers.set("Content-Type", "application/json");

  const started = now();
  let response: Response;
  try {
    response = await (transport.fetch ?? fetch)(url, {
      method,
      headers,
      body: hasBody ? JSON.stringify(options.body) : undefined,
      cache: "no-store",
      signal: options.signal,
    });
  } catch (error) {
    if (isAbort(error)) throw error;
    return unreachable(transport, method, url, requestId, null, Math.round(now() - started));
  }

  // The headers can arrive and the body still fail: the connection drops, or
  // the console restarts mid-answer. That is the same failure as no answer.
  let body: unknown = null;
  if (method !== "HEAD") {
    try {
      body = await readBody(response);
    } catch (error) {
      if (isAbort(error)) throw error;
      return unreachable(transport, method, url, requestId, response.headers.get("x-request-id"), Math.round(now() - started));
    }
  }

  const ok = response.ok;
  const result: ApiResponse<T> = {
    ok,
    status: response.status,
    data: ok ? (body as T) : null,
    body,
    error: ok ? null : classify(response.status, body, response.headers.get("retry-after")),
    location: response.headers.get("location"),
    requestId,
    echoedRequestId: response.headers.get("x-request-id"),
    ms: Math.round(now() - started),
  };
  log(transport, method, url, result);
  return result;
}

function isAbort(error: unknown): boolean {
  return error instanceof DOMException && error.name === "AbortError";
}

/** Status 0: no usable answer. The caller's own abort is rethrown instead. */
function unreachable<T>(
  transport: Transport,
  method: string,
  url: string,
  requestId: string,
  echoedRequestId: string | null,
  ms: number,
): ApiResponse<T> {
  const result: ApiResponse<T> = {
    ok: false,
    status: 0,
    data: null,
    body: null,
    error: classify(0, null),
    location: null,
    requestId,
    echoedRequestId,
    ms,
  };
  log(transport, method, url, result);
  return result;
}

async function readBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (text.length === 0) return null;
  const type = response.headers.get("content-type") ?? "";
  if (!type.includes("json")) return text;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function log<T>(transport: Transport, method: string, url: string, result: ApiResponse<T>): void {
  transport.onLog?.({
    id: result.requestId,
    at: new Date().toISOString(),
    method,
    path: url,
    status: result.status,
    code: result.error?.code ?? null,
    requestId: result.requestId,
    echoedRequestId: result.echoedRequestId,
    location: result.location,
    ms: result.ms,
  });
}
