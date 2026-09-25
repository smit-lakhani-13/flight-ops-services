"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState, type ReactNode } from "react";
import { RequestLogProvider, useRequestLog } from "@/lib/request-log";
import { SessionProvider, useSession } from "@/lib/session";
import { RequestLog } from "./RequestLog";
import { Button } from "./ui";

const NAV = [
  { href: "/", label: "Overview" },
  { href: "/flights", label: "Flights" },
  { href: "/book", label: "Book" },
  { href: "/ops", label: "Ops" },
];

export function Shell({ children }: { children: ReactNode }) {
  return (
    <RequestLogProvider>
      <SessionProvider>
        <Frame>{children}</Frame>
      </SessionProvider>
    </RequestLogProvider>
  );
}

function Frame({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const { session, signOut } = useSession();
  const { entries } = useRequestLog();
  const [logOpen, setLogOpen] = useState(false);

  return (
    <div className="flex min-h-screen flex-col">
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <Link href="/" className="flex items-center gap-2 font-semibold tracking-tight">
            <span aria-hidden className="inline-block h-5 w-5 rounded bg-sky-700" />
            flight-ops console
          </Link>
          <nav aria-label="Main" className="flex flex-wrap gap-1 text-sm">
            {NAV.map((item) => {
              const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
              return (
                <Link
                  key={item.href}
                  href={item.href}
                  aria-current={active ? "page" : undefined}
                  className={`rounded-md px-2.5 py-1.5 ${active ? "bg-slate-100 font-medium dark:bg-slate-800" : "text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800"}`}
                >
                  {item.label}
                </Link>
              );
            })}
          </nav>
          <div className="ml-auto flex items-center gap-2 text-sm">
            <Button tone="ghost" onClick={() => setLogOpen((open) => !open)} aria-expanded={logOpen}>
              Requests <span className="rounded bg-slate-200 px-1.5 text-xs tabular-nums dark:bg-slate-800">{entries.length}</span>
            </Button>
            {session ? (
              <>
                <span className="text-slate-600 dark:text-slate-400" data-testid="signed-in-as">
                  Signed in as <strong className="font-mono">{session.user}</strong>
                </span>
                <Button tone="secondary" onClick={signOut}>
                  Sign out
                </Button>
              </>
            ) : (
              <span className="text-slate-500">Not signed in</span>
            )}
          </div>
        </div>
      </header>
      <main className="mx-auto w-full max-w-6xl flex-1 px-4 py-8">{children}</main>
      <footer className="border-t border-slate-200 py-4 text-center text-xs text-slate-500 dark:border-slate-800">
        Every call goes to this console&apos;s server, which forwards it to the API and keeps nothing. Built and tested in
        CI; never hosted.
      </footer>
      {logOpen && <RequestLog onClose={() => setLogOpen(false)} />}
    </div>
  );
}
