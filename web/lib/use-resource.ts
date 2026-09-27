"use client";

import { useCallback, useEffect, useRef, useState } from "react";

/**
 * Runs `load` when it changes and on reload(), and keeps the latest answer.
 * An answer that arrives after a newer load started is dropped, so a slow
 * page-one response cannot overwrite page two. `load` must be memoised with
 * useCallback, or this loads on every render.
 *
 * set() puts a write's answer in place. A load that started before it may
 * have been read before the write committed, so its answer is dropped too,
 * and a late read cannot bring back the record the write replaced.
 *
 * `pending` is true from the start of a load until its answer arrives, so a
 * page can mark the answer it still shows as out of date.
 */
export function useResource<T>(load: () => Promise<T>): {
  value: T | null;
  pending: boolean;
  reload: () => void;
  set: (value: T) => void;
} {
  const [value, setValue] = useState<T | null>(null);
  const [version, setVersion] = useState(0);
  // The load and version whose answer arrived last. Pending is worked out
  // from it rather than set when a load starts, so the effect sets no state.
  const [settled, setSettled] = useState<{ load: () => Promise<T>; version: number } | null>(null);
  // How many times set() has run. A load keeps the count it started with.
  const writes = useRef(0);

  useEffect(() => {
    let current = true;
    const writesBefore = writes.current;
    load().then(
      (next) => {
        if (!current) return;
        // A write made while this load was out wins; the load still settles.
        if (writes.current === writesBefore) setValue(next);
        setSettled({ load, version });
      },
      (error: unknown) => {
        // apiRequest turns every failure into a result, so a rejection here
        // is a bug. Report it rather than leave the promise unhandled.
        if (!current) return;
        console.error("A console load failed", error);
        setSettled({ load, version });
      },
    );
    return () => {
      current = false;
    };
  }, [load, version]);

  const reload = useCallback(() => setVersion((v) => v + 1), []);
  const set = useCallback((next: T) => {
    writes.current += 1;
    setValue(next);
  }, []);
  const pending = settled === null || settled.load !== load || settled.version !== version;
  return { value, pending, reload, set };
}
