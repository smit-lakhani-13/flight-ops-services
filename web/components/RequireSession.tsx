"use client";

import type { ReactNode } from "react";
import { useSession } from "@/lib/session";
import { SignInPanel } from "./SignInPanel";

export function RequireSession({ children }: { children: ReactNode }) {
  const { session } = useSession();
  return session ? <>{children}</> : <SignInPanel />;
}
