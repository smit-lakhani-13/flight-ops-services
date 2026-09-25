"use client";

import Link from "next/link";
import { HealthCard } from "@/components/HealthCard";
import { SignInPanel } from "@/components/SignInPanel";
import { Card, PageTitle } from "@/components/ui";
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

export default function Overview() {
  const { session } = useSession();
  return (
    <div className="flex flex-col gap-6">
      <PageTitle
        title="Flight operations console"
        subtitle="A front end for every operation of flight-ops-service. The browser talks only to this console's server, which forwards each call to the API with your credentials and stores nothing. The API's security rules are unchanged."
      />
      <div className="grid gap-6 lg:grid-cols-[1fr_22rem]">
        <div className="grid content-start gap-4 sm:grid-cols-2">
          {DEMOS.map((demo) => (
            <Link
              key={demo.href}
              href={demo.href}
              className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm transition hover:border-sky-400 hover:shadow dark:border-slate-800 dark:bg-slate-900 dark:hover:border-sky-700"
            >
              <h2 className="font-semibold">{demo.title}</h2>
              <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">{demo.body}</p>
            </Link>
          ))}
        </div>
        <div className="flex flex-col gap-4">
          <HealthCard title="Service health" path="actuator/health" anonymous />
          {session ? (
            <Card title="Signed in">
              <p className="text-sm">
                As <code>{session.user}</code>. {session.user === "ops" ? "This account reads the actuator; the flight pages will answer 403." : "Pick a demo on the left."}
              </p>
            </Card>
          ) : (
            <SignInPanel />
          )}
        </div>
      </div>
    </div>
  );
}
