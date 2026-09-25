"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useCallback, useRef, useState, type ReactNode } from "react";
import { RequestLogProvider, useRequestLog } from "@/lib/request-log";
import { SessionProvider, useSession } from "@/lib/session";
import { ListIcon, PlaneIcon } from "./icons";
import { RequestLog } from "./RequestLog";
import { Button, FOCUS, MUTED, TOUCH } from "./ui";

const NAV = [
  { href: "/", label: "Overview" },
  { href: "/flights", label: "Flights" },
  { href: "/book", label: "Book" },
  { href: "/ops", label: "Ops" },
];

/**
 * Whether a nav link names the page at `pathname`: the overview only on
 * itself, the others on their own path and anything below it. A plain prefix
 * test would mark Book current on /bookings/7.
 */
export function isCurrent(href: string, pathname: string): boolean {
  if (href === "/") return pathname === "/";
  return pathname === href || pathname.startsWith(`${href}/`);
}

export function Shell({ children }: { children: ReactNode }) {
  return (
    <RequestLogProvider>
      <SessionProvider>
        <Frame>{children}</Frame>
      </SessionProvider>
    </RequestLogProvider>
  );
}

// The header wraps rather than folding into a menu: on a phone the brand and
// Requests share the first row, the nav takes the second and the account the
// third; from 1024 px they sit on one row.
function Frame({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { session, signOut } = useSession();
  const { entries } = useRequestLog();
  const [logOpen, setLogOpen] = useState(false);
  const requests = useRef<HTMLButtonElement>(null);

  const closeLog = useCallback((returnFocus: boolean) => {
    setLogOpen(false);
    if (returnFocus) requests.current?.focus();
  }, []);

  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className={`sr-only rounded-control bg-white px-3 py-2 text-sm font-medium text-sky-800 shadow-card focus:not-sr-only focus:absolute focus:top-2 focus:left-2 focus:z-30 dark:bg-slate-900 dark:text-sky-300 ${FOCUS}`}
      >
        Skip to content
      </a>
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-4 gap-y-2 px-4 py-2.5 sm:px-6">
          <Link
            href="/"
            className={`mr-auto flex items-center gap-2 rounded-control font-semibold tracking-tight lg:mr-0 ${FOCUS} ${TOUCH}`}
          >
            <span className="flex size-7 items-center justify-center rounded-md bg-accent-strong text-on-accent">
              <PlaneIcon />
            </span>
            flight-ops console
          </Link>
          <nav aria-label="Main" className="order-3 w-full md:order-2 md:w-auto md:flex-1">
            <ul className="flex flex-wrap gap-1 text-sm">
              {NAV.map((item) => {
                const current = isCurrent(item.href, pathname);
                return (
                  <li key={item.href}>
                    <Link
                      href={item.href}
                      aria-current={current ? "page" : undefined}
                      className={`inline-flex items-center rounded-control px-3 py-1.5 font-medium motion-safe:transition-colors ${FOCUS} ${TOUCH} ${
                        current
                          ? "bg-sky-50 text-sky-800 dark:bg-sky-400/10 dark:text-sky-300"
                          : "text-slate-700 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"
                      }`}
                    >
                      {item.label}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </nav>
          <Button
            ref={requests}
            tone="ghost"
            icon={<ListIcon />}
            className="md:order-3"
            onClick={() => (logOpen ? closeLog(false) : setLogOpen(true))}
            aria-expanded={logOpen}
            aria-controls="request-log-drawer"
          >
            Requests{" "}
            <span className="rounded-full bg-slate-200 px-1.5 text-xs text-slate-800 tabular-nums dark:bg-slate-700 dark:text-slate-100">
              {entries.length}
            </span>
          </Button>
          <div className="order-4 flex w-full min-w-0 items-center justify-between gap-3 text-sm lg:w-auto lg:justify-end">
            {session ? (
              <>
                <span className={`min-w-0 truncate ${MUTED}`} data-testid="signed-in-as" title={session.user}>
                  Signed in as <strong className="font-mono text-slate-900 dark:text-slate-100">{session.user}</strong>
                </span>
                <Button tone="secondary" onClick={signOut}>
                  Sign out
                </Button>
              </>
            ) : (
              <span className={MUTED}>Not signed in</span>
            )}
          </div>
        </div>
      </header>
      <main id="main" className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 sm:px-6 sm:py-8">
        {children}
      </main>
      <footer className="border-t border-slate-200 dark:border-slate-800">
        <div className={`mx-auto flex max-w-6xl flex-col gap-1 px-4 py-4 text-xs sm:flex-row sm:justify-between sm:gap-4 sm:px-6 ${MUTED}`}>
          <p>Every call goes to this console&apos;s server, which forwards it to the API and keeps nothing.</p>
          <p>Built and tested in CI, never hosted.</p>
        </div>
      </footer>
      {logOpen && (
        <>
          {/* Room to scroll the page's end clear of the drawer. */}
          <div aria-hidden="true" className="h-[50vh] shrink-0" />
          <RequestLog onClose={closeLog} />
        </>
      )}
    </div>
  );
}
