import type { Metadata } from "next";
import type { ReactNode } from "react";
import { forTemplate, shortened } from "@/lib/titles";

// Names the tab on a full load with the same text the page's heading sets.
export async function generateMetadata({ params }: { params: Promise<{ flightNumber: string }> }): Promise<Metadata> {
  const { flightNumber } = await params;
  return { title: forTemplate(shortened(flightNumber)) };
}

export default function Layout({ children }: { children: ReactNode }) {
  return children;
}
