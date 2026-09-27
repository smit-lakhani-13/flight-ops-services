"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { CreateFlightForm } from "@/components/CreateFlightForm";
import { ErrorBanner } from "@/components/ErrorBanner";
import { FlightTable } from "@/components/FlightTable";
import { CloseIcon, InboxIcon, PlusIcon, RefreshIcon, SearchIcon } from "@/components/icons";
import { Pager } from "@/components/Pager";
import { RequireSession } from "@/components/RequireSession";
import { Button, Card, EmptyState, Field, focusIsInOrLost, focusPageTitle, PageTitle, Select, Skeleton, TextInput } from "@/components/ui";
import { useApi } from "@/lib/session";
import type { Flight, Page } from "@/lib/types";
import { useResource } from "@/lib/use-resource";

const SORTS = [
  { value: "departureTime,asc", label: "Departure, earliest first" },
  { value: "departureTime,desc", label: "Departure, latest first" },
  { value: "flightNumber,asc", label: "Flight number" },
  { value: "availableSeats,asc", label: "Fewest seats left" },
  { value: "status,asc", label: "Status" },
];

function foundText({ totalElements, number, totalPages }: Page<Flight>["page"]): string {
  if (totalElements === 0) return "0 flights found";
  const count = totalElements === 1 ? "1 flight" : `${totalElements} flights`;
  return `${count} found, page ${number + 1} of ${Math.max(totalPages, 1)}`;
}

interface Filters {
  origin: string;
  destination: string;
  sort: string;
  page: number;
}

export default function FlightsPage() {
  return (
    <>
      <PageTitle
        title="Flights"
        subtitle="GET /api/v1/flights, paged and sorted by the service; POST /api/v1/flights to create one. Airport codes are trimmed and upper-cased by the service, not here."
      />
      <RequireSession>
        <Flights />
      </RequireSession>
    </>
  );
}

function Flights() {
  const api = useApi();
  const [draft, setDraft] = useState({ origin: "", destination: "" });
  const [filters, setFilters] = useState<Filters>({ origin: "", destination: "", sort: SORTS[0]!.value, page: 0 });
  const [creating, setCreating] = useState(false);
  const createForm = useRef<HTMLDivElement>(null);
  // Set when the empty list's button opens the form, which sits above the
  // list. That button goes away as the form opens, so the focus moves to the
  // form's first field instead of falling back to the page.
  const revealForm = useRef(false);

  useEffect(() => {
    if (creating && revealForm.current) {
      revealForm.current = false;
      createForm.current?.scrollIntoView({ block: "start" });
      createForm.current?.querySelector("input")?.focus({ preventScroll: true });
    }
  }, [creating]);

  const load = useCallback(
    () =>
      api.get<Page<Flight>>("v1/flights", {
        origin: filters.origin,
        destination: filters.destination,
        sort: filters.sort,
        page: filters.page,
        size: 10,
      }),
    [api, filters],
  );
  const { value: result, pending, reload } = useResource(load);
  // Said once each answer arrives, and emptied while a search is out, so the
  // same count twice is still a change a screen reader announces.
  const found = pending || !result?.ok || !result.data ? "" : foundText(result.data.page);
  const failed = result !== null && !(result.ok && result.data);
  const stale = pending && result !== null && !failed;

  // A failed read replaces the table and the pager, so a Next or Previous
  // that asked for it is gone; the focus goes to Try again, which reads the
  // same page with the same filters. Once that read succeeds Try again goes
  // too, and the focus goes to the page's heading.
  const tryAgain = useRef<HTMLButtonElement>(null);
  const retried = useRef(false);
  useEffect(() => {
    if (pending) return;
    if (failed) {
      if (focusIsInOrLost(null)) tryAgain.current?.focus();
    } else if (retried.current) {
      retried.current = false;
      if (focusIsInOrLost(null)) focusPageTitle();
    }
  }, [pending, failed]);
  function retry() {
    retried.current = true;
    reload();
  }

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFilters((current) => ({ ...current, origin: draft.origin, destination: draft.destination, page: 0 }));
  }

  return (
    <div className="flex flex-col gap-6">
      <Card
        title="Search"
        actions={
          // A disclosure: the name stays put and aria-expanded carries the
          // state, so a screen reader hears one change, not two.
          <Button
            tone={creating ? "secondary" : "primary"}
            icon={creating ? <CloseIcon /> : <PlusIcon />}
            onClick={() => setCreating((open) => !open)}
            aria-expanded={creating}
            aria-controls="create-flight"
          >
            New flight
          </Button>
        }
      >
        <form
          onSubmit={search}
          aria-label="Search flights"
          className="grid items-end gap-3 sm:grid-cols-2 xl:grid-cols-[minmax(0,1fr)_minmax(0,1fr)_minmax(15rem,1.5fr)_auto]"
        >
          <Field label="Origin">
            <TextInput name="origin" placeholder="EWR" autoCapitalize="characters" value={draft.origin} onChange={(e) => setDraft({ ...draft, origin: e.target.value })} />
          </Field>
          <Field label="Destination">
            <TextInput
              name="destination"
              placeholder="SFO"
              autoCapitalize="characters"
              value={draft.destination}
              onChange={(e) => setDraft({ ...draft, destination: e.target.value })}
            />
          </Field>
          <Field label="Sort">
            <Select name="sort" value={filters.sort} onChange={(e) => setFilters({ ...filters, sort: e.target.value, page: 0 })}>
              {SORTS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </Select>
          </Field>
          <div className="flex flex-wrap gap-2">
            <Button type="submit" icon={<SearchIcon />}>
              Search
            </Button>
            <Button
              tone="ghost"
              aria-label="Clear filters"
              onClick={() => {
                setDraft({ origin: "", destination: "" });
                setFilters({ ...filters, origin: "", destination: "", page: 0 });
              }}
            >
              Clear
            </Button>
          </div>
        </form>
      </Card>

      {creating && (
        <div id="create-flight" ref={createForm} className="scroll-mt-4">
          <Card title="New flight">
            <CreateFlightForm />
          </Card>
        </div>
      )}

      <Card>
        <h2 className="sr-only">Results</h2>
        <p role="status" className="sr-only">
          {found}
        </p>
        {/* While a new page, sort or search is out, the last answer stays on
            screen, dimmed and marked busy, so it does not pass for the new one.
            After a failure only Try again stays, busy itself. */}
        <div aria-busy={stale} className={stale ? "opacity-60" : undefined}>
          {result === null ? (
            <Skeleton rows={5} label="Loading flights" />
          ) : result.ok && result.data ? (
            result.data.content.length === 0 ? (
              <EmptyState
                icon={<InboxIcon className="size-5" />}
                action={
                  !creating && (
                    <Button
                      icon={<PlusIcon />}
                      onClick={() => {
                        revealForm.current = true;
                        setCreating(true);
                      }}
                    >
                      New flight
                    </Button>
                  )
                }
              >
                No flights match this search.
              </EmptyState>
            ) : (
              <>
                <FlightTable flights={result.data.content} />
                <Pager page={result.data.page} onPage={(page) => setFilters({ ...filters, page })} />
              </>
            )
          ) : (
            <div className="flex flex-col items-start gap-3">
              {!pending && <ErrorBanner error={result.error} />}
              <Button ref={tryAgain} tone="secondary" icon={<RefreshIcon />} busy={pending} onClick={retry}>
                Try again
              </Button>
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}
