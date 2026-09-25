"use client";

import { useCallback, useEffect, useRef, useState, type FormEvent } from "react";
import { CreateFlightForm } from "@/components/CreateFlightForm";
import { ErrorBanner } from "@/components/ErrorBanner";
import { FlightTable } from "@/components/FlightTable";
import { CloseIcon, InboxIcon, PlusIcon, SearchIcon } from "@/components/icons";
import { Pager } from "@/components/Pager";
import { RequireSession } from "@/components/RequireSession";
import { Button, Card, EmptyState, Field, PageTitle, Select, Skeleton, TextInput } from "@/components/ui";
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
  // Set when the empty list's button opens the form, which sits above the list.
  const revealForm = useRef(false);

  useEffect(() => {
    if (creating && revealForm.current) {
      revealForm.current = false;
      createForm.current?.scrollIntoView({ block: "start" });
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
  const { value: result } = useResource(load);

  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setFilters((current) => ({ ...current, origin: draft.origin, destination: draft.destination, page: 0 }));
  }

  return (
    <div className="flex flex-col gap-6">
      <Card
        title="Search"
        actions={
          <Button
            tone={creating ? "secondary" : "primary"}
            icon={creating ? <CloseIcon /> : <PlusIcon />}
            onClick={() => setCreating((open) => !open)}
            aria-expanded={creating}
            aria-controls="create-flight"
          >
            {creating ? "Close the form" : "New flight"}
          </Button>
        }
      >
        {creating && (
          <div id="create-flight" ref={createForm} className="mb-5 scroll-mt-4 border-b border-slate-200 pb-5 dark:border-slate-800">
            <CreateFlightForm />
          </div>
        )}
        <form onSubmit={search} aria-label="Search flights" className="grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-4">
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

      <Card>
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
          <ErrorBanner error={result.error} />
        )}
      </Card>
    </div>
  );
}
