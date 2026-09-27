"use client";

import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState, useSyncExternalStore, type KeyboardEvent } from "react";
import { useRequestLog } from "@/lib/request-log";
import { CheckIcon, CloseIcon, CopyIcon, ListIcon } from "./icons";
import { Button, EmptyState, HttpStatus, Identifier, MUTED, NONE, STATUS_EDGE, statusClass } from "./ui";

// The ids here are the ones to search for in the API's log: every line it
// writes for a request carries the X-Request-Id it echoed. The drawer sits over
// the bottom of the page and its header stays put while the rows scroll. It
// takes the keyboard's focus when it opens, since it comes last on the page;
// Escape inside it closes it and hands focus back to the Requests button. A
// click inside it keeps the focus there, so Escape still works after a click on
// a button that disables itself, and Clear keeps the drawer at its height until
// the next call, so a second click on it lands in the drawer too. Below 640 px
// a row has two cells: the call with the id sent under it, and the answer,
// whose code breaks only after an underscore. A table cannot move one cell's
// content under another's with CSS alone, so the drawer reads the width and
// draws that shape itself; a copy button that had the focus when the width
// crossed 640 px hands it to its twin in the new shape, so Escape still works.
// From 640 px up the time, the id sent, the echo, the
// Location and the duration each have a column, and a long call wraps rather
// than widening every row. On a short screen, or at a high zoom, the whole
// drawer scrolls as one, and the scroll padding keeps a focused row clear of
// the sticky headings. A copied id is said once, in the drawer's one status
// line, while its button keeps its name and its title: a title that changed
// would change the focused button's description, which a screen reader can
// say as well.

/** Tailwind's sm breakpoint, in rem as Tailwind writes it. */
const WIDE_SCREEN = "(min-width: 40rem)";

/**
 * Whether the screen is 640 px or wider; true where there is no matchMedia to
 * ask. `beforeChange` runs when the width crosses 640 px, before the other
 * shape is drawn, while the old one is still on the page.
 */
function useWideScreen(beforeChange: () => void): boolean {
  const subscribe = useCallback(
    (onChange: () => void) => {
      const query = window.matchMedia?.(WIDE_SCREEN);
      const changed = () => {
        beforeChange();
        onChange();
      };
      query?.addEventListener("change", changed);
      return () => query?.removeEventListener("change", changed);
    },
    [beforeChange],
  );
  return useSyncExternalStore(subscribe, () => window.matchMedia?.(WIDE_SCREEN).matches ?? true, () => true);
}

export function RequestLog({ onClose }: { onClose: (returnFocus: boolean) => void }) {
  const { entries, clear } = useRequestLog();
  const close = useRef<HTMLButtonElement>(null);
  const drawer = useRef<HTMLElement>(null);
  const [cleared, setCleared] = useState<number>();
  const [copied, setCopied] = useState<string>();
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const title = useId();
  // The other shape draws every row afresh, which takes a focused copy button
  // off the page and would drop the focus outside the drawer.
  const refocus = useRef<string | null>(null);
  const noteFocus = useCallback(() => {
    const focused = document.activeElement;
    refocus.current = focused instanceof HTMLElement && drawer.current?.contains(focused) ? (focused.dataset.copy ?? null) : null;
  }, []);
  const wide = useWideScreen(noteFocus);

  useEffect(() => close.current?.focus(), []);
  useEffect(() => () => clearTimeout(copiedTimer.current), []);
  useLayoutEffect(() => {
    const value = refocus.current;
    refocus.current = null;
    if (value === null) return;
    const twin = [...(drawer.current?.querySelectorAll<HTMLElement>("[data-copy]") ?? [])].find(
      (button) => button.dataset.copy === value,
    );
    (twin ?? drawer.current)?.focus();
  }, [wide]);

  const onCopied = useCallback((value: string) => {
    setCopied(value);
    clearTimeout(copiedTimer.current);
    copiedTimer.current = setTimeout(() => setCopied(undefined), 1500);
  }, []);

  function onKeyDown(event: KeyboardEvent<HTMLElement>) {
    if (event.key !== "Escape" || event.defaultPrevented || event.nativeEvent.isComposing) return;
    event.preventDefault();
    onClose(true);
  }

  return (
    <aside
      id="request-log-drawer"
      ref={drawer}
      aria-label="Request log"
      tabIndex={-1}
      onKeyDown={onKeyDown}
      style={entries.length === 0 && cleared ? { minHeight: `min(${cleared}px, 50vh)` } : undefined}
      className="fixed inset-x-0 bottom-0 z-20 flex max-h-[50vh] flex-col overflow-y-auto border-t border-slate-300 bg-white shadow-drawer dark:border-slate-700 dark:bg-slate-900"
    >
      <div className="flex flex-wrap items-center justify-between gap-x-3 gap-y-2 border-b border-slate-200 px-4 py-2 sm:px-6 dark:border-slate-800">
        <div className="min-w-0">
          <h2 id={title} className="text-sm font-semibold">
            Request log
          </h2>
          <p className={`text-xs ${MUTED}`}>
            Last {entries.length} of up to 20 calls from this tab, newest first
          </p>
          <p role="status" className="sr-only">
            {copied ? `Copied ${copied}` : ""}
          </p>
        </div>
        <div className="flex gap-2">
          <Button
            tone="ghost"
            disabled={entries.length === 0}
            onClick={() => {
              setCleared(drawer.current?.offsetHeight);
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
        <div className="relative min-h-0 flex-1 scroll-pt-8 overflow-auto [@media(max-height:30rem)]:flex-none">
          <table className="w-full text-left text-xs" data-testid="request-log" aria-labelledby={title}>
            <thead className={MUTED}>
              <tr>
                {(wide ? HEADINGS : NARROW_HEADINGS).map(({ label, className }) => (
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
                const sent = <CopyButton value={entry.requestId} copied={copied === entry.requestId} onCopied={onCopied} />;
                if (!wide) {
                  return (
                    <tr key={entry.id} data-status-class={kind} className="border-t border-slate-100 first:border-t-0 dark:border-slate-800">
                      <td className={`border-l-4 py-1.5 pr-2 pl-4 ${STATUS_EDGE[kind]}`}>
                        <span className="block wrap-anywhere">
                          {entry.method} {entry.path}
                        </span>
                        <span className="flex items-center gap-1">
                          {sent}
                          <span data-testid="sent-id" className="min-w-0 wrap-anywhere">
                            {entry.requestId}
                          </span>
                        </span>
                      </td>
                      <td className="px-2 py-1.5 align-top">
                        <HttpStatus status={entry.status} />
                        {entry.code && (
                          <span className="mt-1 block">
                            <Identifier value={entry.code} />
                          </span>
                        )}
                      </td>
                    </tr>
                  );
                }
                return (
                  <tr key={entry.id} data-status-class={kind} className="border-t border-slate-100 first:border-t-0 dark:border-slate-800">
                    <td className={`border-l-4 py-1.5 pr-2 pl-6 whitespace-nowrap ${STATUS_EDGE[kind]}`}>
                      {new Date(entry.at).toLocaleTimeString()}
                    </td>
                    <td className="min-w-48 px-2 py-1.5 wrap-anywhere">
                      {entry.method} {entry.path}
                    </td>
                    <td className="px-2 py-1.5 whitespace-nowrap">
                      <HttpStatus status={entry.status} /> {entry.code}
                    </td>
                    <td className="px-2 py-1 whitespace-nowrap">
                      <span className="inline-flex items-center gap-1">
                        {sent}
                        <span data-testid="sent-id">{entry.requestId}</span>
                      </span>
                    </td>
                    <td className="px-2 py-1.5 whitespace-nowrap" data-testid="echoed-id">
                      {entry.echoedRequestId === entry.requestId ? "same" : (entry.echoedRequestId ?? NONE)}
                    </td>
                    <td className="px-2 py-1.5 whitespace-nowrap">{entry.location ?? ""}</td>
                    <td className="py-1.5 pr-6 pl-2 text-right tabular-nums">{entry.ms}</td>
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
  { label: "Time", className: "pr-2 pl-7" },
  { label: "Call", className: "px-2" },
  { label: "Status", className: "px-2" },
  { label: "X-Request-Id sent", className: "px-2" },
  { label: "Echoed", className: "px-2" },
  { label: "Location", className: "px-2" },
  { label: "ms", className: "pr-6 pl-2 text-right" },
];

const NARROW_HEADINGS = [
  { label: "Call and X-Request-Id sent", className: "pr-2 pl-5" },
  { label: "Status", className: "px-2" },
];

// A copy button beside an id. The clipboard can refuse (an insecure origin, a
// denied permission); the id is still there to select by hand, so a refusal is
// silently ignored.
function CopyButton({ value, copied, onCopied }: { value: string; copied: boolean; onCopied: (value: string) => void }) {
  async function copy() {
    try {
      await navigator.clipboard.writeText(value);
      onCopied(value);
    } catch {
      // Nothing to do: see above.
    }
  }

  return (
    <Button
      tone="ghost"
      iconOnly
      aria-label={`Copy ${value}`}
      title="Copy"
      data-copy={value}
      icon={copied ? <CheckIcon className="size-4 text-emerald-600 dark:text-emerald-400" /> : <CopyIcon />}
      onClick={copy}
    />
  );
}
