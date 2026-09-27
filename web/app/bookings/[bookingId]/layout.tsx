import type { Metadata } from "next";
import type { ReactNode } from "react";
import { forTemplate, LONGEST_BOOKING_ID, shortened } from "@/lib/titles";

// Names the tab on a full load with the same text the page's heading sets.
export async function generateMetadata({ params }: { params: Promise<{ bookingId: string }> }): Promise<Metadata> {
  const { bookingId } = await params;
  return { title: forTemplate(`Booking #${shortened(bookingId, LONGEST_BOOKING_ID)}`) };
}

export default function Layout({ children }: { children: ReactNode }) {
  return children;
}
