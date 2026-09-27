import type { Metadata } from "next";
import type { ReactNode } from "react";

// Names the tab on a full load, before the page's own code has run; the
// page's heading then sets the same title.
export const metadata: Metadata = { title: "Ops" };

export default function Layout({ children }: { children: ReactNode }) {
  return children;
}
