"use client";

import { useCallback, useEffect } from "react";
import { useApi } from "@/lib/session";
import type { Health } from "@/lib/types";
import { useResource } from "@/lib/use-resource";
import { ErrorBanner } from "./ErrorBanner";
import { Card } from "./ui";

function isHealth(value: unknown): value is Health {
  return typeof value === "object" && value !== null && typeof (value as { status?: unknown }).status === "string";
}

export function HealthCard({ title, path, anonymous, refreshKey = 0 }: { title: string; path: string; anonymous: boolean; refreshKey?: number }) {
  const api = useApi();
  const load = useCallback(() => (anonymous ? api.anonymous<Health>(path) : api.get<Health>(path)), [api, anonymous, path]);
  const { value: result, reload } = useResource(load);

  useEffect(() => {
    if (refreshKey > 0) reload();
  }, [refreshKey, reload]);

  // A health endpoint answers 503, with the same body, while the status is DOWN.
  const health = result?.data ?? (result?.status === 503 && isHealth(result.body) ? result.body : null);
  const status = health?.status ?? null;

  return (
    <Card title={title}>
      {result === null ? (
        <p className="text-sm text-slate-500">Checking…</p>
      ) : status ? (
        <div className="flex flex-col gap-2" data-testid={`health-${path.replaceAll("/", "-")}${anonymous ? "" : "-signed-in"}`}>
          <p className="flex items-center gap-2 text-sm">
            <span aria-hidden className={`inline-block h-2.5 w-2.5 rounded-full ${status === "UP" ? "bg-emerald-500" : "bg-rose-500"}`} />
            <span className="font-mono font-semibold" data-testid="health-status">
              {status}
            </span>
            <code className="text-xs text-slate-500">GET /{path}</code>
          </p>
          {health?.components ? (
            <ul className="grid gap-1 text-xs sm:grid-cols-2" data-testid="health-components">
              {Object.entries(health.components).map(([name, component]) => (
                <li key={name} className="flex justify-between rounded bg-slate-50 px-2 py-1 dark:bg-slate-950">
                  <span className="font-mono">{name}</span>
                  <span className="font-mono">{component.status}</span>
                </li>
              ))}
            </ul>
          ) : (
            <p className="text-xs text-slate-500">No components shown: only the ops account sees what is behind the status.</p>
          )}
        </div>
      ) : (
        <ErrorBanner error={result.error} />
      )}
    </Card>
  );
}
