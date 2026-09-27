import type { Metadata } from "next";
import type { ReactNode } from "react";

// Names the tab on a full load, before the page's own code has run; the
// page's heading then sets the same title. The template is the root layout's
// again, because a plain title here would drop it for a flight's own page.
export const metadata: Metadata = { title: { default: "Flights", template: "%s · flight-ops console" } };

export default function Layout({ children }: { children: ReactNode }) {
  return children;
}
