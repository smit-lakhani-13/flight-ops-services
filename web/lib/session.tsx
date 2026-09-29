"use client";

import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from "react";
import { apiRequest, type ApiResponse, type Query } from "./api";
import { basicAuthorization } from "./base64";
import type { ClassifiedError } from "./errors";
import { useRequestLogActions } from "./request-log";
import type { Health } from "./types";

// Credentials live in this React state and nowhere else: not in a cookie, not
// in localStorage, and not in sessionStorage, which browsers write to disk to
// restore a session. A reload forgets them, by design. The console's server
// never sees them except on the one request it is forwarding.

export interface Session {
  user: string;
  authorization: string;
  /**
   * What the sign-in probe found: true when the account may call the API,
   * false for an account like ops that holds only the actuator's role. The
   * service matches names without regard to case, so the typed name cannot
   * tell the two apart.
   */
  apiScopes: boolean;
}

interface SessionValue {
  session: Session | null;
  signIn: (user: string, password: string) => Promise<ClassifiedError | null>;
  signOut: () => void;
}

const SessionContext = createContext<SessionValue | null>(null);

function hasComponents(body: unknown): boolean {
  if (typeof body !== "object" || body === null) return false;
  const components = (body as Health).components;
  return typeof components === "object" && components !== null;
}

export function SessionProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<Session | null>(null);
  const { record } = useRequestLogActions();

  const signIn = useCallback(
    async (user: string, password: string): Promise<ClassifiedError | null> => {
      let authorization: string;
      try {
        authorization = basicAuthorization(user.trim(), password);
      } catch (error) {
        return {
          kind: "rejected",
          status: 0,
          code: "CONSOLE_BAD_CREDENTIALS",
          message: error instanceof Error ? error.message : "Invalid credentials.",
          fieldErrors: {},
          retryAfter: null,
        };
      }
      // Health answers every valid account and 401 to a wrong password, so it
      // proves the password without a refusal the browser would log. Only the
      // actuator's role sees its components, and neither of the service's
      // two accounts holds that role and the API scopes, so an account that
      // sees them is ops and needs no API read. A 503 proves the password
      // too: health sends it when a component is down, and only once the
      // credential has been accepted.
      const health = await apiRequest<Health>("GET", "actuator/health", { authorization }, { onLog: record });
      if (!health.ok && health.status !== 503) return health.error;
      if (hasComponents(health.body)) {
        setSession({ user: user.trim(), authorization, apiScopes: false });
        return null;
      }
      // Any other account reads one page of flights. A 403 still signs it in:
      // the password is good, and the account holds no API scope.
      const probe = await apiRequest("GET", "v1/flights", { authorization, query: { size: 1 } }, { onLog: record });
      if (probe.ok || probe.status === 403) {
        setSession({ user: user.trim(), authorization, apiScopes: probe.ok });
        return null;
      }
      return probe.error;
    },
    [record],
  );

  const signOut = useCallback(() => setSession(null), []);
  const value = useMemo(() => ({ session, signIn, signOut }), [session, signIn, signOut]);
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionValue {
  const value = useContext(SessionContext);
  if (value === null) throw new Error("useSession outside SessionProvider");
  return value;
}

export interface Api {
  get: <T>(path: string, query?: Query) => Promise<ApiResponse<T>>;
  post: <T>(path: string, body: unknown) => Promise<ApiResponse<T>>;
  patch: <T>(path: string, body: unknown) => Promise<ApiResponse<T>>;
  del: <T>(path: string) => Promise<ApiResponse<T>>;
  /** A GET with no credentials, whoever is signed in. */
  anonymous: <T>(path: string) => Promise<ApiResponse<T>>;
}

export function useApi(): Api {
  const { session } = useSession();
  const { record } = useRequestLogActions();
  const authorization = session?.authorization ?? null;

  return useMemo<Api>(() => {
    const transport = { onLog: record };
    return {
      get: (path, query) => apiRequest("GET", path, { authorization, query }, transport),
      post: (path, body) => apiRequest("POST", path, { authorization, body }, transport),
      patch: (path, body) => apiRequest("PATCH", path, { authorization, body }, transport),
      del: (path) => apiRequest("DELETE", path, { authorization }, transport),
      anonymous: (path) => apiRequest("GET", path, { authorization: null }, transport),
    };
  }, [authorization, record]);
}
