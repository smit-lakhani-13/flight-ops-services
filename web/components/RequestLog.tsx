"use client";

import { useEffect, useRef, useState, type KeyboardEvent } from "react";
import { useRequestLog } from "@/lib/request-log";
import { CheckIcon, CloseIcon, CopyIcon, ListIcon } from "./icons";
import { Button, EmptyState, HttpStatus, MUTED, NONE, STATUS_EDGE, statusClass } from "./ui";

// The ids here are the ones to search for in the API's log: every line it
// writes for a request carries the X-Request-Id it echoed. The drawer sits over
// the bottom of the page and its header stays put while the rows scroll. It
// takes the keyboard's focus when it opens, since it comes last on the page;
// Escape inside it closes it and hands focus back to the Requests button.
// Below 640 px a row shows the call, the answer and the id sent; the time,
// the echo, the Location and the duration join from there up.
const WIDE = "hidden sm:table-cell";

export function RequestLog({ onClose }: { onClose: (returnFocus: boolean) => void }) {
  const { entries, clear } = useRequestLog();
  const close = useRef<HTMLButtonElement>(null);

  useEffect(() => close.current?.focus(), []);

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== "Escape" || event.defaultPrevented || event.nativeEvent.isComposing) return;
    event.preventDefault();
    onClose(true);
  }

  return (
    <aside
      id="request-log-drawer"
      aria-label="Request log"
      onKeyDown={onKeyDown}
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
          <Button
            tone="ghost"
            disabled={entries.length === 0}
            onClick={() => {
              clear();
              // Clear disables itself, which would drop the focus.
              close.current?.focus();
            }}
          >
            Clear
          </Button>
          <Button ref={close} tone="secondary" icon={<CloseIcon />} onClick={() => onClose(true)}>
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
                {HEADINGS.map(({ label, className }) => (
                  <th
                    key={label}
                    scope="col"
                    className={`sticky top-0 z-10 border-b border-slate-200 bg-white py-1.5 font-medium whitespace-nowrap dark:border-slate-800 dark:bg-slate-900 ${className}`}
                  >
                    {label}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="font-mono">
              {entries.map((entry) => {
                const kind = statusClass(entry.status);
                return (
                  <tr key={entry.id} data-status-class={kind} className="border-t border-slate-100 first:border-t-0 dark:border-slate-800">
                    <td className={`${WIDE} border-l-4 py-1.5 pr-2 pl-6 whitespace-nowrap ${STATUS_EDGE[kind]}`}>
                      {new Date(entry.at).toLocaleTimeString()}
                    </td>
                    {/* The status edge moves to this cell while the time is hidden. */}
                    <td className={`border-l-4 py-1.5 pr-2 pl-4 whitespace-nowrap sm:border-l-0 sm:pl-2 ${STATUS_EDGE[kind]}`}>
                      {entry.method} {entry.path}
                    </td>
                    <td className="px-2 py-1.5 whitespace-nowrap">
                      <HttpStatus status={entry.status} /> {entry.code}
                    </td>
                    <td className="px-2 py-1 whitespace-nowrap">
                      <span className="inline-flex items-center gap-1">
                        <CopyButton value={entry.requestId} />
                        <span data-testid="sent-id">{entry.requestId}</span>
                      </span>
                    </td>
                    <td className={`${WIDE} px-2 py-1.5 whitespace-nowrap`} data-testid="echoed-id">
                      {entry.echoedRequestId === entry.requestId ? "same" : (entry.echoedRequestId ?? NONE)}
                    </td>
                    <td className={`${WIDE} px-2 py-1.5 whitespace-nowrap`}>{entry.location ?? ""}</td>
                    <td className={`${WIDE} py-1.5 pr-6 pl-2 text-right tabular-nums`}>{entry.ms}</td>
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

const HEADINGS = [
  { label: "Time", className: `${WIDE} pr-2 pl-7` },
  { label: "Call", className: "pr-2 pl-5 sm:pl-2" },
  { label: "Status", className: "px-2" },
  { label: "X-Request-Id sent", className: "px-2" },
  { label: "Echoed", className: `${WIDE} px-2` },
  { label: "Location", className: `${WIDE} px-2` },
  { label: "ms", className: `${WIDE} pr-6 pl-2 text-right` },
];

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
