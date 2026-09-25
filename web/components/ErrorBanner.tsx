import { hint, type ClassifiedError } from "@/lib/errors";

// Renders whatever the API answered: its code, its message, and any per-field
// messages no input on the page claimed. The console adds only a hint about
// what to do next.

const TONE: Record<ClassifiedError["kind"], string> = {
  unauthenticated: "border-rose-300 bg-rose-50 dark:border-rose-900 dark:bg-rose-950/40",
  forbidden: "border-rose-300 bg-rose-50 dark:border-rose-900 dark:bg-rose-950/40",
  validation: "border-amber-300 bg-amber-50 dark:border-amber-900 dark:bg-amber-950/40",
  notFound: "border-slate-300 bg-slate-100 dark:border-slate-700 dark:bg-slate-900",
  conflict: "border-amber-300 bg-amber-50 dark:border-amber-900 dark:bg-amber-950/40",
  unavailable: "border-orange-300 bg-orange-50 dark:border-orange-900 dark:bg-orange-950/40",
  server: "border-rose-300 bg-rose-50 dark:border-rose-900 dark:bg-rose-950/40",
  console: "border-violet-300 bg-violet-50 dark:border-violet-900 dark:bg-violet-950/40",
  network: "border-violet-300 bg-violet-50 dark:border-violet-900 dark:bg-violet-950/40",
  rejected: "border-amber-300 bg-amber-50 dark:border-amber-900 dark:bg-amber-950/40",
};

export function ErrorBanner({
  error,
  claimedFields = [],
}: {
  error: ClassifiedError | null;
  /** Fields whose messages the form already shows beside the input. */
  claimedFields?: readonly string[];
}) {
  if (error === null) return null;
  const unclaimed = Object.entries(error.fieldErrors).filter(([field]) => !claimedFields.includes(field));
  const advice = hint(error);
  return (
    <div role="alert" data-testid="error-banner" data-code={error.code} className={`rounded-md border p-3 text-sm ${TONE[error.kind]}`}>
      <div className="flex flex-wrap items-baseline gap-x-2">
        {error.status > 0 && <span className="font-mono text-xs text-slate-500">{error.status}</span>}
        <span className="font-mono font-semibold">{error.code}</span>
      </div>
      <p className="mt-1">{error.message}</p>
      {unclaimed.length > 0 && (
        <ul className="mt-2 list-disc pl-5">
          {unclaimed.map(([field, message]) => (
            <li key={field}>
              <code>{field}</code>: {message}
            </li>
          ))}
        </ul>
      )}
      {advice && <p className="mt-2 text-xs text-slate-600 dark:text-slate-400">{advice}</p>}
    </div>
  );
}
