import { summariseRace } from "@/lib/race-summary";
import { isErrorBody, type RaceReport } from "@/lib/types";
import { HttpStatus, TextLink } from "./ui";

export function RaceResult({ report, before, after }: { report: RaceReport; before: number | null; after: number | null }) {
  const { statuses, bookingIds } = summariseRace(report.rows);
  const debited = before !== null && after !== null ? before - after : null;
  return (
    <div className="flex flex-col gap-3" data-testid="race-result">
      <dl className="grid gap-3 sm:grid-cols-4">
        <Stat label="Requests" value={`${report.rows.length} in ${report.totalMs} ms`} />
        <Stat
          label="Answers"
          value={Object.entries(statuses)
            .map(([status, count]) => `${count} × ${status === "0" ? "no answer" : status}`)
            .join(", ")}
          testId="race-statuses"
        />
        <Stat
          label="Distinct bookings"
          value={bookingIds.length === 0 ? "none" : `${bookingIds.length} (${bookingIds.map((id) => `#${id}`).join(", ")})`}
          testId="race-distinct"
        />
        <Stat
          label="Seats left"
          value={before !== null && after !== null ? `${before} → ${after}` : "—"}
          testId="race-seats"
          note={debited !== null ? `${debited} debited` : undefined}
        />
      </dl>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead className="text-slate-500">
            <tr>
              <th className="py-1.5 pr-3 font-medium">#</th>
              <th className="py-1.5 pr-3 font-medium">Status</th>
              <th className="py-1.5 pr-3 font-medium">Answer</th>
              <th className="py-1.5 pr-3 font-medium">X-Request-Id</th>
              <th className="py-1.5 pr-3 font-medium">Echoed</th>
              <th className="py-1.5 text-right font-medium">ms</th>
            </tr>
          </thead>
          <tbody className="font-mono">
            {report.rows.map((row) => {
              const body = row.body as { bookingId?: number } | null;
              return (
                <tr key={row.index} className="border-t border-slate-100 dark:border-slate-800">
                  <td className="py-1.5 pr-3">{row.index + 1}</td>
                  <td className="py-1.5 pr-3">
                    <HttpStatus status={row.status} />
                  </td>
                  <td className="py-1.5 pr-3">
                    {typeof body?.bookingId === "number" ? (
                      <TextLink href={`/bookings/${body.bookingId}`}>booking #{body.bookingId}</TextLink>
                    ) : isErrorBody(row.body) ? (
                      row.body.code
                    ) : (
                      "—"
                    )}
                  </td>
                  <td className="py-1.5 pr-3 whitespace-nowrap">{row.requestId}</td>
                  <td className="py-1.5 pr-3">{row.echoedRequestId === row.requestId ? "same" : (row.echoedRequestId ?? "—")}</td>
                  <td className="py-1.5 text-right tabular-nums">{row.ms}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function Stat({ label, value, note, testId }: { label: string; value: string; note?: string; testId?: string }) {
  return (
    <div className="rounded-md bg-slate-50 p-3 dark:bg-slate-950">
      <dt className="text-xs uppercase tracking-wide text-slate-500">{label}</dt>
      <dd className="mt-1 font-mono text-sm font-semibold" data-testid={testId}>
        {value}
      </dd>
      {note && <dd className="text-xs text-slate-500">{note}</dd>}
    </div>
  );
}
