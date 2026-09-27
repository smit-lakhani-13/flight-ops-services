import type { Metadata } from "next";
import { Overview } from "@/components/Overview";

// The overview itself runs in the browser, since it reads the session; this
// server page names the tab on a full load, before that code has run. It
// shares the root layout's segment, where that layout's template does not
// apply, so the title is written out whole.
export const metadata: Metadata = { title: { absolute: "Overview · flight-ops console" } };

export default function OverviewPage() {
  return <Overview />;
}
