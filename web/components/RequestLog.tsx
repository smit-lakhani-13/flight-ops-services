"use client";

import { useRequestLog } from "@/lib/request-log";
import { Button, HttpStatus } from "./ui";

// The ids here are the ones to search for in the API's log: every line it
// writes for a request carries the X-Request-Id it echoed.
export function RequestLog({ onClose }: { onClose: () => void }) {
  const { entries, clear } = useRequestLog();
  return (
    <aside
      aria-label="Request log"
      className="fixed inset-x-0 bottom-0 z-20 max-h-[45vh] overflow-auto border-t border-slate-300 bg-white/95 shadow-2xl backdrop-blur dark:border-slate-700 dark:bg-slate-900/95"
    >
      <div className="sticky top-0 flex items-center justify-between border-b border-slate-200 bg-inherit px-4 py-2 dark:border-slate-800">
        <h2 className="text-sm font-semibold">Last {entries.length} requests from this tab</h2>
        <div className="flex gap-2">
          <Button tone="ghost" onClick={clear}>
            Clear
          </Button>
          <Button tone="secondary" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
      {entries.length === 0 ? (
        <p className="px-4 py-3 text-sm text-slate-500">No requests yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-xs" data-testid="request-log">
            <thead className="text-slate-500">
              <tr>
                <th className="px-4 py-1.5 font-medium">Time</th>
                <th className="px-2 py-1.5 font-medium">Call</th>
                <th className="px-2 py-1.5 font-medium">Status</th>
                <th className="px-2 py-1.5 font-medium">X-Request-Id sent</th>
                <th className="px-2 py-1.5 font-medium">Echoed</th>
                <th className="px-2 py-1.5 font-medium">Location</th>
                <th className="px-4 py-1.5 text-right font-medium">ms</th>
              </tr>
            </thead>
            <tbody className="font-mono">
              {entries.map((entry) => (
                <tr key={entry.id} className="border-t border-slate-100 dark:border-slate-800">
                  <td className="px-4 py-1.5 whitespace-nowrap">{new Date(entry.at).toLocaleTimeString()}</td>
                  <td className="px-2 py-1.5 whitespace-nowrap">
                    {entry.method} {entry.path}
                  </td>
                  <td className="px-2 py-1.5 whitespace-nowrap">
                    <HttpStatus status={entry.status} /> {entry.code}
                  </td>
                  <td className="px-2 py-1.5 whitespace-nowrap" data-testid="sent-id">
                    {entry.requestId}
                  </td>
                  <td className="px-2 py-1.5 whitespace-nowrap" data-testid="echoed-id">
                    {entry.echoedRequestId === entry.requestId ? "same" : (entry.echoedRequestId ?? "—")}
                  </td>
                  <td className="px-2 py-1.5 whitespace-nowrap">{entry.location ?? ""}</td>
                  <td className="px-4 py-1.5 text-right tabular-nums">{entry.ms}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </aside>
  );
}
