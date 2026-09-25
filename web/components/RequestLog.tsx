"use client";

import { useEffect, useRef, useState } from "react";
import { useRequestLog } from "@/lib/request-log";
import { CheckIcon, CloseIcon, CopyIcon, ListIcon } from "./icons";
import { Button, EmptyState, HttpStatus, MUTED, NONE, STATUS_EDGE, statusClass } from "./ui";

// The ids here are the ones to search for in the API's log: every line it
// writes for a request carries the X-Request-Id it echoed. The drawer sits over
// the bottom of the page; its header stays put while the rows scroll, and
// Escape closes it and hands focus back to the Requests button.
export function RequestLog({ onClose }: { onClose: (returnFocus: boolean) => void }) {
  const { entries, clear } = useRequestLog();

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose(true);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  return (
    <aside
      id="request-log-drawer"
      aria-label="Request log"
      className="fixed inset-x-0 bottom-0 z-20 flex max-h-[50vh] flex-col border-t border-slate-300 bg-white shadow-drawer dark:border-slate-700 dark:bg-slate-900"
    >
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 border-b border-slate-200 px-4 py-2 sm:px-6 dark:border-slate-800">
        <div className="min-w-0">
          <h2 className="text-sm font-semibold">Request log</h2>
          <p className={`text-xs ${MUTED}`}>
            Last {entries.length} of up to 20 calls from this tab, newest first
          </p>
        </div>
        <div className="flex gap-2">
          <Button tone="ghost" onClick={clear} disabled={entries.length === 0}>
            Clear
          </Button>
          <Button tone="secondary" icon={<CloseIcon />} onClick={() => onClose(true)}>
            Close
          </Button>
        </div>
      </div>
      {entries.length === 0 ? (
        <EmptyState icon={<ListIcon className="size-5" />}>
          No requests yet. Every call a page makes is listed here with the id the API logged it under.
        </EmptyState>
      ) : (
        <div className="relative min-h-0 flex-1 overflow-auto">
          <table className="w-full text-left text-xs" data-testid="request-log">
            <thead className={MUTED}>
              <tr>
                {["Time", "Call", "Status", "X-Request-Id sent", "Echoed", "Location", "ms"].map((heading, index, all) => (
                  <th
                    key={heading}
                    scope="col"
                    className={`sticky top-0 z-10 border-b border-slate-200 bg-white py-1.5 font-medium whitespace-nowrap dark:border-slate-800 dark:bg-slate-900 ${
                      index === 0 ? "pr-2 pl-5 sm:pl-7" : index === all.length - 1 ? "pr-4 pl-2 text-right sm:pr-6" : "px-2"
                    }`}
                  >
                    {heading}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="font-mono">
              {entries.map((entry) => {
                const kind = statusClass(entry.status);
                return (
                  <tr key={entry.id} data-status-class={kind} className="border-t border-slate-100 first:border-t-0 dark:border-slate-800">
                    <td className={`border-l-4 py-1.5 pr-2 pl-4 whitespace-nowrap sm:pl-6 ${STATUS_EDGE[kind]}`}>
                      {new Date(entry.at).toLocaleTimeString()}
                    </td>
                    <td className="px-2 py-1.5 whitespace-nowrap">
                      {entry.method} {entry.path}
                    </td>
                    <td className="px-2 py-1.5 whitespace-nowrap">
                      <HttpStatus status={entry.status} /> {entry.code}
                    </td>
                    <td className="px-2 py-1 whitespace-nowrap">
                      <span className="inline-flex items-center gap-1">
                        <span data-testid="sent-id">{entry.requestId}</span>
                        <CopyButton value={entry.requestId} />
                      </span>
                    </td>
                    <td className="px-2 py-1.5 whitespace-nowrap" data-testid="echoed-id">
                      {entry.echoedRequestId === entry.requestId ? "same" : (entry.echoedRequestId ?? NONE)}
                    </td>
                    <td className="px-2 py-1.5 whitespace-nowrap">{entry.location ?? ""}</td>
                    <td className="py-1.5 pr-4 pl-2 text-right tabular-nums sm:pr-6">{entry.ms}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </aside>
  );
}

// A copy button beside an id. The clipboard can refuse (an insecure origin, a
// denied permission); the id is still there to select by hand, so a refusal is
// silently ignored.
function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => clearTimeout(timer.current), []);

  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(true);
      clearTimeout(timer.current);
      timer.current = setTimeout(() => setCopied(false), 1500);
    } catch {
      // Nothing to do: see above.
    }
  }

  return (
    <Button
      tone="ghost"
      iconOnly
      aria-label={copied ? `Copied ${value}` : `Copy ${value}`}
      title={copied ? "Copied" : "Copy"}
      icon={copied ? <CheckIcon className="size-4 text-emerald-600 dark:text-emerald-400" /> : <CopyIcon />}
      onClick={copy}
    />
  );
}
