import type { Metadata } from "next";
import { CompassIcon } from "@/components/icons";
import { Card, EmptyState, PageTitle, TextLink } from "@/components/ui";

export const metadata: Metadata = { title: "Not found" };

export default function NotFound() {
  return (
    <>
      <PageTitle title="Not found" />
      <Card>
        <EmptyState icon={<CompassIcon className="size-5" />} action={
            <TextLink href="/" standalone>
              Back to the overview
            </TextLink>
          }>
          The console has no page at this address.
        </EmptyState>
      </Card>
    </>
  );
}
