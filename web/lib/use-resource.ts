"use client";

import { useCallback, useEffect, useState } from "react";

/**
 * Runs `load` when it changes and on reload(), and keeps the latest answer.
 * An answer that arrives after a newer load started is dropped, so a slow
 * page-one response cannot overwrite page two. `load` must be memoised with
 * useCallback, or this loads on every render.
 */
export function useResource<T>(load: () => Promise<T>): {
  value: T | null;
  reload: () => void;
  set: (value: T) => void;
} {
  const [value, setValue] = useState<T | null>(null);
  const [version, setVersion] = useState(0);

  useEffect(() => {
    let current = true;
    load().then((next) => {
      if (current) setValue(next);
    });
    return () => {
      current = false;
    };
  }, [load, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  return { value, reload, set: setValue };
}
