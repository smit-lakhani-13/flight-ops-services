"use client";

import { useState, type FormEvent } from "react";
import type { ClassifiedError } from "@/lib/errors";
import { useSession } from "@/lib/session";
import { ErrorBanner } from "./ErrorBanner";
import { Button, Card, Field, MUTED, TextInput } from "./ui";

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
    try {
      const failure = await signIn(user, password);
      if (failure) {
        setError(failure);
        setPassword("");
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card title="Sign in" className="mx-auto w-full max-w-md">
      <form onSubmit={submit} className="flex flex-col gap-4" aria-label="Sign in">
        <p className={`text-sm text-pretty ${MUTED}`}>
          Use the API&apos;s own accounts. The default profile has two:{" "}
          <code>api</code> / <code>dev-secret</code> for the flight and booking operations, and <code>ops</code> /{" "}
          <code>dev-ops</code> for the actuator.
        </p>
        <Field label="User">
          <TextInput name="user" autoComplete="username" autoCapitalize="none" spellCheck={false} value={user} onChange={(e) => setUser(e.target.value)} required />
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
        <Button type="submit" busy={busy} disabled={user.length === 0}>
          Sign in
        </Button>
        <p className={`text-xs text-pretty ${MUTED}`}>
          The password stays in this tab&apos;s memory and goes only to this console&apos;s server, which forwards it
          on each call. Reloading the page forgets it.
        </p>
      </form>
    </Card>
  );
}
