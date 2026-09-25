"use client";

import { useState, type FormEvent } from "react";
import type { ClassifiedError } from "@/lib/errors";
import { useSession } from "@/lib/session";
import { ErrorBanner } from "./ErrorBanner";
import { Button, Card, Field, TextInput } from "./ui";

export function SignInPanel() {
  const { signIn } = useSession();
  const [user, setUser] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<ClassifiedError | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    const failure = await signIn(user, password);
    setBusy(false);
    if (failure) {
      setError(failure);
      setPassword("");
    }
  }

  return (
    <Card title="Sign in" className="mx-auto max-w-md">
      <form onSubmit={submit} className="flex flex-col gap-3" aria-label="Sign in">
        <p className="text-sm text-slate-600 dark:text-slate-400">
          Use the API&apos;s own accounts. The default profile has two:{" "}
          <code>api</code> / <code>dev-secret</code> for the flight and booking operations, and <code>ops</code> /{" "}
          <code>dev-ops</code> for the actuator.
        </p>
        <Field label="User">
          <TextInput name="user" autoComplete="username" value={user} onChange={(e) => setUser(e.target.value)} required />
        </Field>
        <Field label="Password">
          <TextInput
            name="password"
            type="password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
        </Field>
        <ErrorBanner error={error} />
        <Button type="submit" disabled={busy || user.length === 0}>
          {busy ? "Checking…" : "Sign in"}
        </Button>
        <p className="text-xs text-slate-500">
          The password stays in this tab&apos;s memory and goes only to this console&apos;s server, which forwards it
          on each call. Reloading the page forgets it.
        </p>
      </form>
    </Card>
  );
}
