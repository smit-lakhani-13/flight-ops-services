"use client";

import { AlertIcon, RefreshIcon } from "@/components/icons";
import { Button, Card, EmptyState, PageTitle } from "@/components/ui";

// Catches a page that throws while it draws. The shell sits outside this
// boundary, so the request log and the credential in memory survive it.
export default function PageError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <>
      <PageTitle title="Something went wrong" />
      <Card>
        <EmptyState
          icon={<AlertIcon className="size-5" />}
          action={
            <Button icon={<RefreshIcon />} onClick={reset}>
              Try again
            </Button>
          }
        >
          This page failed while drawing. Trying again redraws it without signing you out
          {error.digest ? ` (reference ${error.digest})` : ""}.
        </EmptyState>
      </Card>
    </>
  );
}
