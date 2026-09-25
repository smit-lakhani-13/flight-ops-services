import type { Page } from "@/lib/types";
import { Button, MUTED } from "./ui";

export function Pager<T>({ page, onPage }: { page: Page<T>["page"]; onPage: (next: number) => void }) {
  const last = Math.max(page.totalPages - 1, 0);
  return (
    <div className={`mt-4 flex flex-wrap items-center justify-between gap-2 text-sm ${MUTED}`}>
      <span className="tabular-nums">
        {page.totalElements} total · page {page.number + 1} of {Math.max(page.totalPages, 1)}
      </span>
      <div className="flex gap-2">
        <Button tone="secondary" disabled={page.number <= 0} onClick={() => onPage(page.number - 1)}>
          Previous
        </Button>
        <Button tone="secondary" disabled={page.number >= last} onClick={() => onPage(page.number + 1)}>
          Next
        </Button>
      </div>
    </div>
  );
}
