import { PageTitle, TextLink } from "@/components/ui";

export default function NotFound() {
  return (
    <div>
      <PageTitle title="Not found" subtitle="The console has no page at this address." />
      <TextLink href="/">Back to the overview</TextLink>
    </div>
  );
}
