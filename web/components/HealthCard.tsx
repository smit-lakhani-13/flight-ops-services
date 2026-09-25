"use client";

import { useCallback, useEffect, useRef } from "react";
import { useApi } from "@/lib/session";
import type { Health } from "@/lib/types";
import { useResource } from "@/lib/use-resource";
import { ErrorBanner } from "./ErrorBanner";
import { Card, MUTED } from "./ui";

function isHealth(value: unknown): value is Health {
  return typeof value === "object" && value !== null && typeof (value as { status?: unknown }).status === "string";
}

/** Wraps each read, so a page can tell when all its cards have answered. */
export type Track = <T>(pending: Promise<T>) => Promise<T>;

export function HealthCard({
  title,
  path,
  anonymous,
  refreshKey = 0,
  track,
}: {
  title: string;
  path: string;
  anonymous: boolean;
  refreshKey?: number;
  track?: Track;
}) {
  const api = useApi();
  const load = useCallback(() => {
    const pending = anonymous ? api.anonymous<Health>(path) : api.get<Health>(path);
    return track ? track(pending) : pending;
  }, [api, anonymous, path, track]);
  const { value: result, reload } = useResource(load);

  // Reload when the key moves, not when the card mounts with a key already
  // above zero: the first load has already started by then.
  const seenKey = useRef(refreshKey);
  useEffect(() => {
    if (refreshKey !== seenKey.current) {
      seenKey.current = refreshKey;
      reload();
    }
  }, [refreshKey, reload]);

  // A health endpoint answers 503, with the same body, while the status is DOWN.
  const health = result?.data ?? (result?.status === 503 && isHealth(result.body) ? result.body : null);
  const status = health?.status ?? null;

  return (
    <Card title={title}>
      {result === null ? (
        <p className={`text-sm ${MUTED}`}>Checking…</p>
      ) : status ? (
        <div className="flex flex-col gap-3" data-testid={`health-${path.replaceAll("/", "-")}${anonymous ? "" : "-signed-in"}`}>
          <p className="flex flex-wrap items-center gap-x-2 gap-y-1 text-sm">
            <span aria-hidden="true" className={`size-2.5 shrink-0 rounded-full ${status === "UP" ? "bg-emerald-500" : "bg-rose-500"}`} />
            <span
              className={`font-mono font-semibold ${status === "UP" ? "text-emerald-700 dark:text-emerald-400" : "text-rose-700 dark:text-rose-400"}`}
              data-testid="health-status"
            >
              {status}
            </span>
            <code className={`break-all ${MUTED}`}>GET /{path}</code>
          </p>
          {health?.components ? (
            <ul className="grid gap-1.5 text-xs sm:grid-cols-2" data-testid="health-components">
              {Object.entries(health.components).map(([name, component]) => (
                <li
                  key={name}
                  className="flex min-w-0 items-center justify-between gap-2 rounded-md bg-slate-50 px-2 py-1.5 dark:bg-slate-950"
                >
                  <span className="min-w-0 font-mono break-all">{name}</span>
                  <span
                    className={`font-mono font-semibold ${
                      component.status === "UP" ? "text-emerald-700 dark:text-emerald-400" : "text-rose-700 dark:text-rose-400"
                    }`}
                  >
                    {component.status}
                  </span>
                </li>
              ))}
            </ul>
          ) : (
            <p className={`text-xs ${MUTED}`}>No components shown: only the ops account sees what is behind the status.</p>
          )}
        </div>
      ) : (
        <ErrorBanner error={result.error} />
      )}
    </Card>
  );
}
