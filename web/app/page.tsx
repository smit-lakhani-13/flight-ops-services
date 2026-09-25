"use client";

import Link from "next/link";
import { HealthCard } from "@/components/HealthCard";
import { ArrowRightIcon } from "@/components/icons";
import { SignInPanel } from "@/components/SignInPanel";
import { Card, FOCUS, MUTED, PageTitle } from "@/components/ui";
import { useSession } from "@/lib/session";

const DEMOS = [
  {
    href: "/book?flight=UA123",
    title: "Book, replay and race one key",
    body: "Book a seat, replay the request, then send ten identical requests at once: one booking, one debit.",
  },
  {
    href: "/flights/UA123",
    title: "Walk a flight through its states",
    body: "Move SCHEDULED to BOARDING, then ask for ARRIVED and read the service's 409.",
  },
  {
    href: "/flights",
    title: "Search, create, and see validation",
    body: "Filter by airport, create a flight, and send bad fields to get one message per field.",
  },
  {
    href: "/ops",
    title: "Health and meters",
    body: "Health without credentials, the components behind it as ops, and the booking and outbox counters.",
  },
];

// On a phone the health tile and the sign-in form come first, so the first
// screen can be used at once; from 1024 px they move to a column on the right.
export default function Overview() {
  const { session } = useSession();
  return (
    <>
      <PageTitle
        title="Flight operations console"
        documentTitle="Overview"
        subtitle="Every operation of flight-ops-service, from the browser. Each call goes to this console's server, which forwards it to the API with your credentials and stores nothing; the API's security rules are unchanged."
      />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_22rem]">
        <div className="flex min-w-0 flex-col gap-4 lg:col-start-2 lg:row-start-1">
          <HealthCard title="Service health" path="actuator/health" anonymous />
          {session ? (
            <Card title="Signed in">
              <p className="text-sm text-pretty">
                As <code className="wrap-anywhere">{session.user}</code>.{" "}
                {session.apiScopes ? "Pick a demo to start." : "This account reads the actuator; the flight pages will answer 403."}
              </p>
            </Card>
          ) : (
            <SignInPanel />
          )}
        </div>
        <section aria-labelledby="demos" className="min-w-0 lg:col-start-1 lg:row-start-1">
          <h2 id="demos" className="mb-3 text-base font-semibold">
            Four guided demos
          </h2>
          <ol className="grid gap-4 sm:grid-cols-2">
            {DEMOS.map((demo, index) => (
              <li key={demo.href} className="min-w-0">
                <Link
                  href={demo.href}
                  className={`group flex h-full gap-4 rounded-card border border-slate-200 bg-white p-4 shadow-card motion-safe:transition-colors hover:border-sky-400 dark:border-slate-800 dark:bg-slate-900 dark:hover:border-sky-600 ${FOCUS}`}
                >
                  <span
                    aria-hidden="true"
                    className="flex size-8 shrink-0 items-center justify-center rounded-full bg-sky-50 font-mono text-sm font-semibold text-sky-800 dark:bg-sky-400/10 dark:text-sky-300"
                  >
                    {index + 1}
                  </span>
                  <div className="flex min-w-0 flex-1 flex-col gap-1">
                    <div className="flex items-start justify-between gap-2">
                      <h3 className="font-semibold text-balance">{demo.title}</h3>
                      <ArrowRightIcon className="mt-0.5 size-4 text-slate-400 group-hover:text-sky-600 dark:text-slate-500 dark:group-hover:text-sky-400" />
                    </div>
                    <p className={`text-sm text-pretty ${MUTED}`}>{demo.body}</p>
                  </div>
                </Link>
              </li>
            ))}
          </ol>
        </section>
      </div>
    </>
  );
}
