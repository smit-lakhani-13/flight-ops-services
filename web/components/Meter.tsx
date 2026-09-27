"use client";

import { useCallback, useEffect, useRef } from "react";
import { useApi } from "@/lib/session";
import type { Metric } from "@/lib/types";
import { useResource } from "@/lib/use-resource";
import { ErrorBanner } from "./ErrorBanner";
import type { Track } from "./HealthCard";
import { MUTED, NONE, Stat } from "./ui";

// One Micrometer meter, read from /actuator/metrics/{name}. The ops account
// can read it; the api account gets the actuator's 403.
export function Meter({
  name,
  description,
  refreshKey = 0,
  track,
}: {
  name: string;
  description: string;
  refreshKey?: number;
  track?: Track;
}) {
  const api = useApi();
  // A meter with one small tag, such as outcome, is read once per value too,
  // so a replay and a new booking show up apart.
  const load = useCallback(() => {
    const pending = (async () => {
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
    })();
    return track ? track(pending) : pending;
  }, [api, name, track]);
  const { value: loaded, reload } = useResource(load);

  // As in HealthCard: reload when the key moves, not on mount.
  const seenKey = useRef(refreshKey);
  useEffect(() => {
    if (refreshKey !== seenKey.current) {
      seenKey.current = refreshKey;
      reload();
    }
  }, [refreshKey, reload]);

  const result = loaded?.whole ?? null;
  const split = loaded?.split ?? null;
  const metric = result?.data;
  return (
    <div
      className="flex min-w-0 flex-col gap-3 rounded-card border border-slate-200 bg-white p-4 shadow-card dark:border-slate-800 dark:bg-slate-900"
      data-testid={`meter-${name}`}
    >
      <div className="min-w-0">
        <h3 className="font-mono text-sm font-semibold wrap-anywhere">{name}</h3>
        <p className={`mt-0.5 text-xs text-pretty ${MUTED}`}>{description}</p>
      </div>
      {result === null ? (
        <p className={`text-sm ${MUTED}`}>Reading…</p>
      ) : metric ? (
        <>
          <dl className="grid grid-cols-2 gap-2">
            {metric.measurements.map((m) => (
              <Stat
                key={m.statistic}
                label={m.statistic.replace("_", " ")}
                value={formatValue(m.value)}
                note={m.statistic.includes("TIME") || m.statistic === "MAX" ? metric.baseUnit : undefined}
                testId={`meter-${name}-${m.statistic}`}
              />
            ))}
          </dl>
          {split ? (
            <p className={`flex flex-wrap gap-2 text-xs ${MUTED}`}>
              {split.counts.map(([value, count]) => (
                <span
                  key={value}
                  className="inline-flex items-center gap-1.5 rounded-full bg-slate-100 px-2 py-0.5 dark:bg-slate-800"
                >
                  <code>
                    {split.tag}={value}
                  </code>
                  <strong className="text-slate-900 tabular-nums dark:text-slate-100" data-testid={`meter-${name}-${value}`}>
                    {count === null ? NONE : formatValue(count)}
                  </strong>
                </span>
              ))}
            </p>
          ) : (
            metric.availableTags.length > 0 && (
              <p className={`text-xs wrap-anywhere ${MUTED}`}>
                Tags:{" "}
                {metric.availableTags.map((t) => (
                  <span key={t.tag} className="mr-2">
                    <code>{t.tag}</code> = {t.values.join(", ")}
                  </span>
                ))}
              </p>
            )
          )}
        </>
      ) : result.status === 404 ? (
        <p className={`text-sm ${MUTED}`}>Not registered yet: the meter appears with its first event.</p>
      ) : (
        <ErrorBanner error={result.error} announce={false} />
      )}
    </div>
  );
}

function formatValue(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toFixed(3);
}
