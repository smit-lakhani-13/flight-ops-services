"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import type { LogEntry } from "./api";

// The last twenty calls this tab made, newest first. It is the console's half
// of following one request: the id it sent, the id the API echoed, and where
// the API's own log line for it can be found (doc/OPERATIONS.md).

export const LOG_LIMIT = 20;

interface RequestLogActions {
  record: (entry: LogEntry) => void;
  clear: () => void;
}

interface RequestLogValue extends RequestLogActions {
  entries: LogEntry[];
}

// Two contexts, so a component that only records is not rendered again when an
// entry is added. Every data component records through useApi, so with one
// context each logged call rendered every card on the page again. The actions
// never change, so a record() renders only what reads the entries: the frame
// around the page (Shell.tsx#Frame, for the Requests count) and the drawer, not
// the page inside it.
const EntriesContext = createContext<LogEntry[] | null>(null);
const ActionsContext = createContext<RequestLogActions | null>(null);

export function appendEntry(entries: readonly LogEntry[], entry: LogEntry): LogEntry[] {
  return [entry, ...entries].slice(0, LOG_LIMIT);
}

export function RequestLogProvider({ children }: { children: ReactNode }) {
  const [entries, setEntries] = useState<LogEntry[]>([]);
  const record = useCallback((entry: LogEntry) => setEntries((current) => appendEntry(current, entry)), []);
  const clear = useCallback(() => setEntries([]), []);
  const actions = useMemo(() => ({ record, clear }), [record, clear]);
  return (
    <ActionsContext.Provider value={actions}>
      <EntriesContext.Provider value={entries}>{children}</EntriesContext.Provider>
    </ActionsContext.Provider>
  );
}

/** record and clear only. A component that uses just these never renders again on a record(). */
export function useRequestLogActions(): RequestLogActions {
  const actions = useContext(ActionsContext);
  if (actions === null) throw new Error("useRequestLogActions outside RequestLogProvider");
  return actions;
}

/** The entries with the actions. A component that uses this renders again on every record(). */
export function useRequestLog(): RequestLogValue {
  const entries = useContext(EntriesContext);
  const actions = useContext(ActionsContext);
  if (entries === null || actions === null) throw new Error("useRequestLog outside RequestLogProvider");
  return useMemo(() => ({ entries, ...actions }), [entries, actions]);
}
