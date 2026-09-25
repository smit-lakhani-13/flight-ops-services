"use client";

import { useCallback, useEffect } from "react";
import { useApi } from "@/lib/session";
import type { Metric } from "@/lib/types";
import { useResource } from "@/lib/use-resource";
import { ErrorBanner } from "./ErrorBanner";

// One Micrometer meter, read from /actuator/metrics/{name}. The ops account
// can read it; the api account gets the actuator's 403.
export function Meter({ name, description, refreshKey = 0 }: { name: string; description: string; refreshKey?: number }) {
  const api = useApi();
  // A meter with one small tag, such as outcome, is read once per value too,
  // so a replay and a new booking show up apart.
  const load = useCallback(async () => {
    const whole = await api.get<Metric>(`actuator/metrics/${name}`);
    const tags = whole.data?.availableTags ?? [];
    const only = tags.length === 1 ? tags[0] : undefined;
    if (!only || only.values.length > 4) return { whole, split: null };
    const counts = await Promise.all(
      [...only.values].sort().map(async (value): Promise<[string, number | null]> => {
        const part = await api.get<Metric>(`actuator/metrics/${name}`, { tag: `${only.tag}:${value}` });
        const first = part.data?.measurements[0];
        return [value, first ? first.value : null];
      }),
    );
    return { whole, split: { tag: only.tag, counts } };
  }, [api, name]);
  const { value: loaded, reload } = useResource(load);

  useEffect(() => {
    if (refreshKey > 0) reload();
  }, [refreshKey, reload]);

  const result = loaded?.whole ?? null;
  const split = loaded?.split ?? null;
  const metric = result?.data;
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3 dark:border-slate-800 dark:bg-slate-900" data-testid={`meter-${name}`}>
      <div className="font-mono text-xs text-slate-500">{name}</div>
      <p className="mb-2 text-xs text-slate-500">{description}</p>
      {result === null ? (
        <p className="text-sm text-slate-500">Reading…</p>
      ) : metric ? (
        <>
          <dl className="flex flex-wrap gap-x-4 gap-y-1">
            {metric.measurements.map((m) => (
              <div key={m.statistic}>
                <dt className="text-[10px] uppercase tracking-wide text-slate-500">{m.statistic.replace("_", " ")}</dt>
                <dd className="font-mono text-lg font-semibold tabular-nums" data-testid={`meter-${name}-${m.statistic}`}>
                  {formatValue(m.value)}
                  {m.statistic.includes("TIME") || m.statistic === "MAX" ? <span className="text-xs text-slate-500"> {metric.baseUnit}</span> : null}
                </dd>
              </div>
            ))}
          </dl>
          {split ? (
            <p className="mt-2 flex flex-wrap gap-x-3 text-xs text-slate-600 dark:text-slate-400">
              {split.counts.map(([value, count]) => (
                <span key={value}>
                  <code>
                    {split.tag}={value}
                  </code>{" "}
                  <strong className="tabular-nums" data-testid={`meter-${name}-${value}`}>
                    {count === null ? "—" : formatValue(count)}
                  </strong>
                </span>
              ))}
            </p>
          ) : metric.availableTags.length > 0 && (
            <p className="mt-2 text-xs text-slate-500">
              Tags:{" "}
              {metric.availableTags.map((t) => (
                <span key={t.tag} className="mr-2">
                  <code>{t.tag}</code> = {t.values.join(", ")}
                </span>
              ))}
            </p>
          )}
        </>
      ) : result.status === 404 ? (
        <p className="text-sm text-slate-500">Not registered yet: the meter appears with its first event.</p>
      ) : (
        <ErrorBanner error={result.error} />
      )}
    </div>
  );
}

function formatValue(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(3);
}
