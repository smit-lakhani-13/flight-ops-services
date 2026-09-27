"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import type { LogEntry } from "./api";

// The last twenty calls this tab made, newest first. It is the console's half
// of following one request: the id it sent, the id the API echoed, and where
// the API's own log line for it can be found (doc/OPERATIONS.md).

export const LOG_LIMIT = 20;

interface RequestLogValue {
  entries: LogEntry[];
  record: (entry: LogEntry) => void;
  clear: () => void;
}

const RequestLogContext = createContext<RequestLogValue | null>(null);

export function appendEntry(entries: readonly LogEntry[], entry: LogEntry): LogEntry[] {
  return [entry, ...entries].slice(0, LOG_LIMIT);
}

export function RequestLogProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const record = useCallback((entry: LogEntry) => setEntries((current) => appendEntry(current, entry)), []);
  const clear = useCallback(() => setEntries([]), []);
  const value = useMemo(() => ({ entries, record, clear }), [entries, record, clear]);
  return <RequestLogContext.Provider value={value}>{children}</RequestLogContext.Provider>;
}

export function useRequestLog(): RequestLogValue {
  const value = useContext(RequestLogContext);
  if (value === null) throw new Error("useRequestLog outside RequestLogProvider");
  return value;
}
