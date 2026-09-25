import { hint, type ClassifiedError } from "@/lib/errors";
import { AlertIcon } from "./icons";

// Renders whatever the API answered: its code, its message, and any per-field
// messages no input on the page claimed. The console adds only a hint about
// what to do next. Codes and messages can be long unbroken words, so they wrap
// anywhere rather than widen a phone's page.
//
// A banner that answers something the user just did is an alert. One that a
// card shows because its read failed passes `announce={false}`: a page of
// cards would otherwise interrupt a screen reader once per card.

const TONE: Record<ClassifiedError["kind"], string> = {
  unauthenticated: "border-rose-300 bg-rose-50 dark:border-rose-900 dark:bg-rose-950/40",
  forbidden: "border-rose-300 bg-rose-50 dark:border-rose-900 dark:bg-rose-950/40",
  validation: "border-amber-300 bg-amber-50 dark:border-amber-900 dark:bg-amber-950/40",
  notFound: "border-slate-300 bg-slate-100 dark:border-slate-700 dark:bg-slate-900",
  conflict: "border-orange-300 bg-orange-50 dark:border-orange-900 dark:bg-orange-950/40",
  unavailable: "border-orange-300 bg-orange-50 dark:border-orange-900 dark:bg-orange-950/40",
  server: "border-rose-300 bg-rose-50 dark:border-rose-900 dark:bg-rose-950/40",
  console: "border-violet-300 bg-violet-50 dark:border-violet-900 dark:bg-violet-950/40",
  network: "border-violet-300 bg-violet-50 dark:border-violet-900 dark:bg-violet-950/40",
  rejected: "border-amber-300 bg-amber-50 dark:border-amber-900 dark:bg-amber-950/40",
};

const ICON: Record<ClassifiedError["kind"], string> = {
  unauthenticated: "text-rose-700 dark:text-rose-400",
  forbidden: "text-rose-700 dark:text-rose-400",
  validation: "text-amber-700 dark:text-amber-400",
  notFound: "text-slate-600 dark:text-slate-400",
  conflict: "text-orange-700 dark:text-orange-400",
  unavailable: "text-orange-700 dark:text-orange-400",
  server: "text-rose-700 dark:text-rose-400",
  console: "text-violet-700 dark:text-violet-400",
  network: "text-violet-700 dark:text-violet-400",
  rejected: "text-amber-700 dark:text-amber-400",
};

export function ErrorBanner({
  error,
  claimedFields = [],
  announce = true,
}: {
  error: ClassifiedError | null;
  /** Fields whose messages the form already shows beside the input. */
  claimedFields?: readonly string[];
  announce?: boolean;
}) {
  if (error === null) return null;
  const unclaimed = Object.entries(error.fieldErrors).filter(([field]) => !claimedFields.includes(field));
  const advice = hint(error);
  return (
    <div
      role={announce ? "alert" : undefined}
      data-testid="error-banner"
      data-code={error.code}
      className={`flex min-w-0 gap-3 rounded-control border p-3 text-sm ${TONE[error.kind]}`}
    >
      <AlertIcon className={`mt-0.5 size-4 ${ICON[error.kind]}`} />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-baseline gap-x-2">
          {error.status > 0 && <span className="font-mono text-xs text-slate-600 dark:text-slate-400">{error.status}</span>}
          <span className="font-mono font-semibold wrap-anywhere">{error.code}</span>
        </div>
        <p className="mt-1 wrap-anywhere">{error.message}</p>
        {unclaimed.length > 0 && (
          <ul className="mt-2 list-disc pl-5">
            {unclaimed.map(([field, message]) => (
              <li key={field} className="wrap-anywhere">
                <code>{field}</code>: {message}
              </li>
            ))}
          </ul>
        )}
        {advice && <p className="mt-2 text-xs text-slate-700 dark:text-slate-300">{advice}</p>}
      </div>
    </div>
  );
}
