"use client";

import { useEffect, useRef } from "react";
import type { Page } from "@/lib/types";
import { Button, focusIsInOrLost, MUTED } from "./ui";

export function Pager<T>({ page, onPage }: { page: Page<T>["page"]; onPage: (next: number) => void }) {
  const last = Math.max(page.totalPages - 1, 0);
  const previous = useRef<HTMLButtonElement>(null);
  const next = useRef<HTMLButtonElement>(null);
  // The button last pressed, until its page arrives.
  const pressed = useRef<"previous" | "next" | null>(null);

  // Reaching the first or the last page disables the button that got there,
  // which would drop the focus, so it moves to the other one.
  useEffect(() => {
    const went = pressed.current;
    pressed.current = null;
    if (!went) return;
    const [from, to] = went === "next" ? [next, previous] : [previous, next];
    if (from.current?.disabled && focusIsInOrLost(from.current)) to.current?.focus();
  }, [page.number]);

  function go(to: number, button: "previous" | "next") {
    pressed.current = button;
    onPage(to);
  }

  return (
    <div className={`mt-4 flex flex-wrap items-center justify-between gap-2 text-sm ${MUTED}`}>
      <span className="tabular-nums">
        {page.totalElements} total · page {page.number + 1} of {Math.max(page.totalPages, 1)}
      </span>
      <div className="flex gap-2">
        <Button ref={previous} tone="secondary" disabled={page.number <= 0} onClick={() => go(page.number - 1, "previous")}>
          Previous
        </Button>
        <Button ref={next} tone="secondary" disabled={page.number >= last} onClick={() => go(page.number + 1, "next")}>
          Next
        </Button>
      </div>
    </div>
  );
}
