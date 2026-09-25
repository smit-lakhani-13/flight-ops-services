import Link from "next/link";
import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode, SelectHTMLAttributes } from "react";
import type { FlightStatus } from "@/lib/types";

// The handful of primitives every page shares. Tailwind classes only, so the
// console carries no component library to keep patched.

type Tone = "primary" | "secondary" | "danger" | "ghost";

const TONES: Record<Tone, string> = {
  primary: "bg-sky-700 text-white hover:bg-sky-800 disabled:bg-sky-700/50 dark:bg-sky-600 dark:hover:bg-sky-500",
  secondary:
    "border border-slate-300 bg-white text-slate-800 hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800",
  danger: "bg-rose-700 text-white hover:bg-rose-800 disabled:bg-rose-700/50",
  ghost: "text-slate-600 hover:bg-slate-200/60 disabled:opacity-50 dark:text-slate-300 dark:hover:bg-slate-800",
};

export function Button({
  tone = "primary",
  className = "",
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: Tone }) {
  return (
    <button
      type="button"
      {...props}
      className={`inline-flex items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-sky-600 disabled:cursor-not-allowed ${TONES[tone]} ${className}`}
    />
  );
}

export function Card({ title, actions, children, className = "" }: { title?: ReactNode; actions?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-lg border border-slate-200 bg-white p-4 shadow-sm dark:border-slate-800 dark:bg-slate-900 ${className}`}>
      {(title || actions) && (
        <header className="mb-3 flex flex-wrap items-center justify-between gap-2">
          {title && <h2 className="text-base font-semibold">{title}</h2>}
          {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

export function PageTitle({ title, subtitle, actions }: { title: ReactNode; subtitle?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 max-w-3xl text-sm text-slate-600 dark:text-slate-400">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

const INPUT =
  "w-full rounded-md border border-slate-300 bg-white px-2.5 py-1.5 text-sm shadow-sm focus:border-sky-600 focus:outline-none focus:ring-1 focus:ring-sky-600 aria-[invalid=true]:border-rose-600 dark:border-slate-700 dark:bg-slate-950";

export function Field({
  label,
  error,
  hint,
  children,
}: {
  label: string;
  error?: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1 text-sm">
      <span className="font-medium text-slate-700 dark:text-slate-300">{label}</span>
      {children}
      {error ? (
        <span role="alert" className="text-xs text-rose-700 dark:text-rose-400">
          {error}
        </span>
      ) : (
        hint && <span className="text-xs text-slate-500">{hint}</span>
      )}
    </label>
  );
}

export function TextInput({ invalid, className = "", ...props }: InputHTMLAttributes<HTMLInputElement> & { invalid?: boolean }) {
  return <input {...props} aria-invalid={invalid ? true : undefined} className={`${INPUT} ${className}`} />;
}

export function Select({ className = "", ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return <select {...props} className={`${INPUT} ${className}`} />;
}

const STATUS_TONES: Record<FlightStatus, string> = {
  SCHEDULED: "bg-sky-100 text-sky-800 dark:bg-sky-950 dark:text-sky-300",
  BOARDING: "bg-indigo-100 text-indigo-800 dark:bg-indigo-950 dark:text-indigo-300",
  DELAYED: "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-300",
  DEPARTED: "bg-violet-100 text-violet-800 dark:bg-violet-950 dark:text-violet-300",
  ARRIVED: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300",
  CANCELLED: "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-300",
};

export function StatusBadge({ status }: { status: FlightStatus }) {
  return (
    <span data-testid="flight-status" className={`inline-flex rounded-full px-2 py-0.5 text-xs font-semibold ${STATUS_TONES[status] ?? ""}`}>
      {status}
    </span>
  );
}

export function HttpStatus({ status }: { status: number }) {
  const tone =
    status === 0
      ? "bg-slate-200 text-slate-800 dark:bg-slate-800 dark:text-slate-200"
      : status < 300
        ? "bg-emerald-100 text-emerald-800 dark:bg-emerald-950 dark:text-emerald-300"
        : status < 500
          ? "bg-amber-100 text-amber-900 dark:bg-amber-950 dark:text-amber-300"
          : "bg-rose-100 text-rose-800 dark:bg-rose-950 dark:text-rose-300";
  return <span className={`inline-flex rounded px-1.5 py-0.5 font-mono text-xs font-semibold ${tone}`}>{status === 0 ? "—" : status}</span>;
}

export function SeatBar({ available, total }: { available: number; total: number }) {
  const sold = total > 0 ? Math.round(((total - available) / total) * 100) : 0;
  return (
    <div className="flex items-center gap-2" title={`${total - available} of ${total} sold`}>
      <div className="h-1.5 w-20 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800">
        <div className="h-full bg-sky-600" style={{ width: `${sold}%` }} />
      </div>
      <span className="tabular-nums text-sm">
        {available}
        <span className="text-slate-500">/{total}</span>
      </span>
    </div>
  );
}

export function TextLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link href={href} className="font-medium text-sky-700 underline-offset-2 hover:underline dark:text-sky-400">
      {children}
    </Link>
  );
}

export function formatInstant(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return iso;
  return date.toLocaleString(undefined, {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    timeZoneName: "short",
  });
}

/** `/api/v1/flights/UA999` from a Location header becomes the console page `/flights/UA999`. */
export function pageFor(location: string | null): string | null {
  if (!location) return null;
  const match = /^\/api\/v1\/(flights|bookings)\/([A-Za-z0-9]+)$/.exec(location);
  return match ? `/${match[1]}/${match[2]}` : null;
}
