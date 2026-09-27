import type { ReactNode } from "react";

// The console's icons, drawn here as inline SVG so it needs no icon package.
// Each is decorative: hidden from assistive technology, sized by className,
// and drawn in currentColor, so the text beside it names the action.

function Icon({ className = "size-4", children }: { className?: string; children: ReactNode }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className={`shrink-0 ${className}`}
    >
      {children}
    </svg>
  );
}

type IconProps = { className?: string };

export function PlaneIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <path d="M12 3.5c.9 0 1.5.8 1.5 1.8V10l7 4v2l-7-2v4l2.5 2v1.5L12 20.5l-4 1V20l2.5-2v-4l-7 2v-2l7-4V5.3c0-1 .6-1.8 1.5-1.8z" />
    </Icon>
  );
}

export function ListIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <path d="M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01" />
    </Icon>
  );
}

export function PlusIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <path d="M12 5v14M5 12h14" />
    </Icon>
  );
}

export function CloseIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <path d="M6 6l12 12M18 6 6 18" />
    </Icon>
  );
}

export function CheckIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <path d="M5 12.5 9.5 17 19 7.5" />
    </Icon>
  );
}

export function CopyIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <rect x="9" y="9" width="11" height="11" rx="2" />
      <path d="M15 9V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v7a2 2 0 0 0 2 2h3" />
    </Icon>
  );
}

export function RefreshIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <path d="M20 12a8 8 0 1 1-2.34-5.66" />
      <path d="M20 4v5h-5" />
    </Icon>
  );
}

export function SearchIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </Icon>
  );
}

export function InboxIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <path d="M4 13 6.5 6h11L20 13v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1z" />
      <path d="M4 13h4.5l1 2h5l1-2H20" />
    </Icon>
  );
}

export function AlertIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <path d="M12 4 21 20H3z" />
      <path d="M12 10v4M12 17h.01" />
    </Icon>
  );
}

export function CompassIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <circle cx="12" cy="12" r="9" />
      <path d="m15.5 8.5-2 5-5 2 2-5z" />
    </Icon>
  );
}

export function ArrowRightIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <path d="M5 12h14M13 6l6 6-6 6" />
    </Icon>
  );
}

/**
 * A busy ring. It does not rotate: the console's only motion is a colour
 * transition, and aria-busy on the control says the rest.
 */
export function SpinnerIcon({ className }: IconProps) {
  return (
    <Icon className={className}>
      <circle cx="12" cy="12" r="8" opacity="0.3" />
      <path d="M20 12a8 8 0 0 0-8-8" />
    </Icon>
  );
}
