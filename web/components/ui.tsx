"use client";

import Link from "next/link";
import { createContext, useContext, useEffect, useId, type ComponentProps, type ReactNode } from "react";
import type { FlightStatus } from "@/lib/types";
import { SpinnerIcon } from "./icons";

// The primitives every page shares, in Tailwind classes alone, so the console
// carries no component library to keep patched. Three rules hold for all of
// them. Below Tailwind's xl breakpoint (1280 px) a control is at least 44 px
// tall and a field's text is at least 16 px, so a finger can hit it and iOS
// does not zoom on focus; from 1280 up the pages keep a desk's density. Every
// control shows the accent outline when the keyboard reaches it. Nothing
// moves but colour.

/** What a page shows where a value is absent: an em dash, which the specs match. */
export const NONE = "\u2014";

/** Secondary text, at 4.5:1 or better on white and on slate-900. */
export const MUTED = "text-slate-600 dark:text-slate-400";

/** The keyboard focus outline, in the accent colour. */
export const FOCUS = "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent";

/** The touch height below 1280 px. */
export const TOUCH = "max-xl:min-h-11";

type Tone = "primary" | "secondary" | "danger" | "ghost";

const TONES: Record<Tone, string> = {
  primary: "bg-accent-strong text-on-accent shadow-xs hover:bg-sky-800 dark:hover:bg-sky-300",
  secondary:
    "border border-slate-300 bg-white text-slate-800 shadow-xs hover:bg-slate-100 dark:border-slate-700 dark:bg-slate-900 dark:text-slate-100 dark:hover:bg-slate-800",
  danger: "bg-rose-700 text-white shadow-xs hover:bg-rose-800 dark:bg-rose-600 dark:hover:bg-rose-500",
  ghost: "text-slate-700 hover:bg-slate-200/70 dark:text-slate-300 dark:hover:bg-slate-800",
};

type ButtonProps = ComponentProps<"button"> & {
  tone?: Tone;
  /** Disables the button and shows a ring in place of its icon until the call returns. */
  busy?: boolean;
  icon?: ReactNode;
  /** A square button holding only an icon; it then needs an aria-label. */
  iconOnly?: boolean;
};

export function Button({ tone = "primary", busy = false, icon, iconOnly = false, disabled, className = "", children, ...props }: ButtonProps) {
  const shape = iconOnly ? "size-8 shrink-0 max-xl:size-11" : `px-3 py-1.5 ${TOUCH}`;
  return (
    <button
      type="button"
      {...props}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      className={`inline-flex items-center justify-center gap-2 rounded-control text-center text-sm font-medium motion-safe:transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${FOCUS} ${shape} ${TONES[tone]} ${className}`}
    >
      {busy ? <SpinnerIcon /> : icon}
      {children}
    </button>
  );
}

export function Card({
  title,
  actions,
  children,
  className = "",
  level = 2,
}: {
  title?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  level?: 2 | 3;
}) {
  const Heading = level === 2 ? "h2" : "h3";
  return (
    <section
      className={`min-w-0 rounded-card border border-slate-200 bg-white p-4 shadow-card sm:p-5 dark:border-slate-800 dark:bg-slate-900 ${className}`}
    >
      {(title || actions) && (
        <header className="mb-4 flex flex-wrap items-center justify-between gap-x-3 gap-y-2">
          {title && <Heading className="min-w-0 text-base font-semibold text-balance">{title}</Heading>}
          {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
        </header>
      )}
      {children}
    </section>
  );
}

/**
 * The page's one h1. It also names the browser tab, from `documentTitle` or
 * from a plain-text title, so each page has a title of its own.
 */
export function PageTitle({
  title,
  subtitle,
  actions,
  documentTitle,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  actions?: ReactNode;
  documentTitle?: string;
}) {
  const text = documentTitle ?? (typeof title === "string" ? title : undefined);
  useEffect(() => {
    if (text) document.title = `${text} · flight-ops console`;
  }, [text]);

  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-x-4 gap-y-3 sm:mb-8">
      <div className="min-w-0">
        <h1 className="text-2xl font-semibold tracking-tight text-balance wrap-anywhere sm:text-3xl">{title}</h1>
        {subtitle && <p className={`mt-2 max-w-3xl text-sm text-pretty sm:text-base ${MUTED}`}>{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  );
}

// A Field hands its control an id, and the id of whichever of its hint or
// error is showing, so the label names the control and the message describes
// it without the page wiring either.
interface FieldState {
  id: string;
  describedBy: string | undefined;
  invalid: boolean;
}

const FieldContext = createContext<FieldState | null>(null);

export function Field({
  label,
  error,
  hint,
  children,
  className = "",
}: {
  label: string;
  error?: string;
  hint?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  const id = useId();
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;
  const describedBy = error ? errorId : hint ? hintId : undefined;
  return (
    <div className={`flex min-w-0 flex-col gap-1.5 text-sm ${className}`}>
      <label htmlFor={id} className="font-medium text-slate-700 dark:text-slate-300">
        {label}
      </label>
      <FieldContext value={{ id, describedBy, invalid: Boolean(error) }}>{children}</FieldContext>
      {error ? (
        <span id={errorId} role="alert" className="text-xs text-rose-700 dark:text-rose-400">
          {error}
        </span>
      ) : (
        hint && (
          <span id={hintId} className={`text-xs ${MUTED}`}>
            {hint}
          </span>
        )
      )}
    </div>
  );
}

const CONTROL = `block w-full min-w-0 rounded-control border border-slate-300 bg-white px-2.5 py-1.5 text-sm text-slate-900 shadow-xs placeholder:text-slate-500 motion-safe:transition-colors focus:border-accent ${FOCUS} aria-[invalid=true]:border-rose-600 dark:border-slate-700 dark:bg-slate-950 dark:text-slate-100 dark:placeholder:text-slate-400 dark:aria-[invalid=true]:border-rose-400 max-xl:min-h-11 max-xl:text-base`;

function useFieldWiring(id: string | undefined, describedBy: string | undefined, invalid: boolean | undefined) {
  const field = useContext(FieldContext);
  const described = [describedBy, field?.describedBy].filter(Boolean).join(" ");
  return {
    id: id ?? field?.id,
    "aria-describedby": described || undefined,
    "aria-invalid": (invalid ?? field?.invalid) ? true : undefined,
  };
}

type Wired = { invalid?: boolean };

export function TextInput({ invalid, id, "aria-describedby": describedBy, className = "", ...props }: ComponentProps<"input"> & Wired) {
  const wiring = useFieldWiring(id, describedBy, invalid);
  return <input {...props} {...wiring} className={`${CONTROL} ${className}`} />;
}

export function Select({ invalid, id, "aria-describedby": describedBy, className = "", ...props }: ComponentProps<"select"> & Wired) {
  const wiring = useFieldWiring(id, describedBy, invalid);
  return <select {...props} {...wiring} className={`${CONTROL} pr-8 ${className}`} />;
}

// Flight statuses and HTTP classes each have a colour of their own. The text
// is the *-800 shade on a *-50 fill in the light scheme and *-300 on a faint
// tint in the dark one, about 7:1 either way.
const STATUS_TONES: Record<FlightStatus, { badge: string; dot: string }> = {
  SCHEDULED: {
    badge: "bg-sky-50 text-sky-800 ring-sky-600/25 dark:bg-sky-400/10 dark:text-sky-300 dark:ring-sky-400/30",
    dot: "bg-sky-500",
  },
  BOARDING: {
    badge: "bg-indigo-50 text-indigo-800 ring-indigo-600/25 dark:bg-indigo-400/10 dark:text-indigo-300 dark:ring-indigo-400/30",
    dot: "bg-indigo-500",
  },
  DELAYED: {
    badge: "bg-amber-50 text-amber-800 ring-amber-600/30 dark:bg-amber-400/10 dark:text-amber-300 dark:ring-amber-400/30",
    dot: "bg-amber-500",
  },
  DEPARTED: {
    badge: "bg-violet-50 text-violet-800 ring-violet-600/25 dark:bg-violet-400/10 dark:text-violet-300 dark:ring-violet-400/30",
    dot: "bg-violet-500",
  },
  ARRIVED: {
    badge: "bg-emerald-50 text-emerald-800 ring-emerald-600/25 dark:bg-emerald-400/10 dark:text-emerald-300 dark:ring-emerald-400/30",
    dot: "bg-emerald-500",
  },
  CANCELLED: {
    badge: "bg-rose-50 text-rose-800 ring-rose-600/25 dark:bg-rose-400/10 dark:text-rose-300 dark:ring-rose-400/30",
    dot: "bg-rose-500",
  },
};

export function StatusBadge({ status }: { status: FlightStatus }) {
  const tone = STATUS_TONES[status];
  return (
    <span
      data-testid="flight-status"
      className={`inline-flex items-center gap-1.5 rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset ${tone?.badge ?? ""}`}
    >
      <span aria-hidden="true" className={`size-1.5 rounded-full ${tone?.dot ?? "bg-slate-400"}`} />
      {status}
    </span>
  );
}

/** An HTTP answer's class. 409 has its own: it is the API refusing a move, not a malformed request. */
export type StatusClass = "2xx" | "3xx" | "4xx" | "409" | "5xx" | "none";

export function statusClass(status: number): StatusClass {
  if (status === 409) return "409";
  if (status >= 200 && status < 300) return "2xx";
  if (status >= 300 && status < 400) return "3xx";
  if (status >= 400 && status < 500) return "4xx";
  if (status >= 500 && status < 600) return "5xx";
  return "none";
}

const STATUS_BADGE: Record<StatusClass, string> = {
  "2xx": "bg-emerald-50 text-emerald-800 ring-emerald-600/25 dark:bg-emerald-400/10 dark:text-emerald-300 dark:ring-emerald-400/30",
  "3xx": "bg-sky-50 text-sky-800 ring-sky-600/25 dark:bg-sky-400/10 dark:text-sky-300 dark:ring-sky-400/30",
  "4xx": "bg-amber-50 text-amber-800 ring-amber-600/30 dark:bg-amber-400/10 dark:text-amber-300 dark:ring-amber-400/30",
  "409": "bg-orange-50 text-orange-800 ring-orange-600/30 dark:bg-orange-400/10 dark:text-orange-300 dark:ring-orange-400/30",
  "5xx": "bg-rose-50 text-rose-800 ring-rose-600/25 dark:bg-rose-400/10 dark:text-rose-300 dark:ring-rose-400/30",
  none: "bg-slate-100 text-slate-700 ring-slate-500/25 dark:bg-slate-400/10 dark:text-slate-300 dark:ring-slate-400/30",
};

/** A left edge in the answer's colour, for a row or a card. */
export const STATUS_EDGE: Record<StatusClass, string> = {
  "2xx": "border-l-emerald-500",
  "3xx": "border-l-sky-500",
  "4xx": "border-l-amber-500",
  "409": "border-l-orange-500",
  "5xx": "border-l-rose-500",
  none: "border-l-slate-400",
};

/** Text in the answer's colour, at 4.5:1 or better in both schemes. */
export const STATUS_TEXT: Record<StatusClass, string> = {
  "2xx": "text-emerald-700 dark:text-emerald-400",
  "3xx": "text-sky-700 dark:text-sky-400",
  "4xx": "text-amber-700 dark:text-amber-400",
  "409": "text-orange-700 dark:text-orange-400",
  "5xx": "text-rose-700 dark:text-rose-400",
  none: "text-slate-700 dark:text-slate-300",
};

export function HttpStatus({ status }: { status: number }) {
  return (
    <span
      className={`inline-flex rounded-md px-1.5 py-0.5 font-mono text-xs font-semibold ring-1 ring-inset ${STATUS_BADGE[statusClass(status)]}`}
    >
      {status === 0 ? NONE : status}
    </span>
  );
}

/** Seats left as a bar and as text: "12/20", read out as "12/20 seats left". */
export function SeatBar({ available, total }: { available: number; total: number }) {
  const sold = total > 0 ? Math.round(((total - available) / total) * 100) : 0;
  const fill = available <= 0 ? "bg-rose-500" : total > 0 && available / total <= 0.1 ? "bg-amber-500" : "bg-accent";
  return (
    <div className="flex items-center gap-2" title={`${total - available} of ${total} sold`}>
      <div aria-hidden="true" className="h-1.5 w-20 shrink-0 overflow-hidden rounded-full bg-slate-200 dark:bg-slate-700">
        <div className={`h-full rounded-full ${fill}`} style={{ width: `${sold}%` }} />
      </div>
      <span className="text-sm whitespace-nowrap tabular-nums">
        {available}
        <span className={MUTED}>/{total}</span>
        <span className="sr-only"> seats left</span>
      </span>
    </div>
  );
}

export interface KeyValueItem {
  label: string;
  value: ReactNode;
  note?: ReactNode;
  testId?: string;
}

/** A record's fields, two or three to a row on wider screens. */
export function KeyValue({ items }: { items: readonly KeyValueItem[] }) {
  return (
    <dl className="grid gap-x-6 gap-y-4 text-sm sm:grid-cols-2 lg:grid-cols-3">
      {items.map((item) => (
        <div key={item.label} className="min-w-0">
          <dt className={`text-xs font-medium tracking-wide uppercase ${MUTED}`}>{item.label}</dt>
          <dd className="mt-1 wrap-anywhere" data-testid={item.testId}>
            {item.value}
          </dd>
          {item.note && <dd className={`mt-0.5 font-mono text-xs wrap-anywhere ${MUTED}`}>{item.note}</dd>}
        </div>
      ))}
    </dl>
  );
}

/** Grey bars where rows will be; the text for a screen reader says what is loading. */
export function Skeleton({ rows = 3, label = "Loading" }: { rows?: number; label?: string }) {
  return (
    <div role="status" className="flex flex-col gap-3 py-2">
      <span className="sr-only">{label}…</span>
      {Array.from({ length: rows }, (_, row) => (
        <div key={row} aria-hidden="true" className="flex items-center gap-4">
          <div className="h-4 w-20 rounded bg-slate-200 dark:bg-slate-800" />
          <div className="h-4 flex-1 rounded bg-slate-100 dark:bg-slate-800/60" />
          <div className="hidden h-4 w-24 rounded bg-slate-200 sm:block dark:bg-slate-800" />
        </div>
      ))}
    </div>
  );
}

/** An icon, one line of text and at most one action, for a list with nothing in it. */
export function EmptyState({ icon, children, action }: { icon: ReactNode; children: ReactNode; action?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-3 px-4 py-8 text-center">
      <span className="flex size-12 items-center justify-center rounded-full bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400">
        {icon}
      </span>
      <p className={`max-w-sm text-sm text-pretty ${MUTED}`}>{children}</p>
      {action}
    </div>
  );
}

/** One figure with its label, for a grid of them inside a dl. */
export function Stat({ label, value, note, testId }: { label: string; value: ReactNode; note?: ReactNode; testId?: string }) {
  return (
    <div className="min-w-0 rounded-control border border-slate-200 bg-slate-50 px-3 py-2.5 dark:border-slate-800 dark:bg-slate-950/50">
      <dt className={`text-xs font-medium ${MUTED}`}>{label}</dt>
      <dd className="mt-1 font-mono text-lg font-semibold tabular-nums wrap-anywhere" data-testid={testId}>
        {value}
      </dd>
      {note && <dd className={`text-xs ${MUTED}`}>{note}</dd>}
    </div>
  );
}

export function TextLink({ href, children, className = "" }: { href: string; children: ReactNode; className?: string }) {
  return (
    <Link
      href={href}
      className={`rounded-sm font-medium text-sky-700 underline-offset-2 hover:underline dark:text-sky-400 ${FOCUS} ${className}`}
    >
      {children}
    </Link>
  );
}

/**
 * A count typed into a field, as the API should receive it: the number when
 * the text is one, and NaN otherwise, which JSON sends as null. parseInt
 * would read "2x" as 2 and "1.5" as 1, and the service would never see what
 * was typed.
 */
export function countFrom(text: string): number {
  const trimmed = text.trim();
  return /^-?(\d+|\d*\.\d+)$/.test(trimmed) ? Number(trimmed) : Number.NaN;
}

export function formatInstant(iso: string | null | undefined): string {
  if (!iso) return NONE;
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
