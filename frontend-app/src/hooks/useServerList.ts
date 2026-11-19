import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import type { DspmError } from '../types/dspm';
import { toDspmError } from './useDspmDatastores';

/** What a server-read view holds. */
export interface ServerList<T> {
  /** The last answer; kept while a newer request runs. */
  data: T | null;
  loading: boolean;
  error: DspmError | null;
  /** Requests the same URL again (Retry, polling). */
  reload: () => void;
}

/**
 * One GET whose URL carries the view's state (filters, search, page): it runs whenever `url` changes, and
 * not at all while `url` is null. As in `useDatastoreList`, a sequence number drops every answer that
 * arrives after a newer request started or after the view unmounted, so a slow page never overwrites a
 * newer one. Errors become a `DspmError` through `toDspmError`.
 */
export function useServerList<T>(url: string | null): ServerList<T> {
  const [data, setData] = useState<T | null>(null);
  const [loading, setLoading] = useState(url !== null);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const seq = useRef(0);

  useEffect(() => {
    if (url === null) {
      setLoading(false);
      return;
    }
    const current = ++seq.current;
    setLoading(true);
    setError(null);
    apiClient.get<T>(url)
      .then(result => { if (current === seq.current) setData(result); })
      .catch(err => { if (current === seq.current) setError(toDspmError(err)); })
      .finally(() => { if (current === seq.current) setLoading(false); });
    // A newer request or the unmount makes this answer stale.
    return () => { seq.current += 1; };
  }, [url, attempt]);

  const reload = useCallback(() => setAttempt(n => n + 1), []);
  return { data, loading, error, reload };
}

/** Search typing pauses this long before the search is sent. */
export const TEXT_DEBOUNCE_MS = 300;

/** `value`, trimmed, once it has not changed for `delayMs`. Starts as the trimmed initial value. */
export function useDebouncedText(value: string, delayMs: number = TEXT_DEBOUNCE_MS): string {
  const [debounced, setDebounced] = useState(() => value.trim());
  useEffect(() => {
    const next = value.trim();
    const timer = window.setTimeout(() => setDebounced(next), delayMs);
    return () => window.clearTimeout(timer);
  }, [value, delayMs]);
  return debounced;
}

/**
 * The interval of every XamLens poll (design 4.9): the smallest that can return fresh data through the
 * backend's 1-minute cache entries.
 */
export const POLL_INTERVAL_MS = 60_000;

/**
 * Calls `reload` every minute while `active` and the browser tab is visible. Stops when `active` turns
 * false, when the tab is hidden (and starts again when it is shown) and on unmount. `reload` is read when
 * the timer fires, so it need not be stable.
 */
export function usePolling(active: boolean, reload: () => void): void {
  const latestReload = useRef(reload);
  useEffect(() => { latestReload.current = reload; });

  useEffect(() => {
    if (!active) return;
    let timer: number | null = null;
    const start = () => {
      if (timer === null && document.visibilityState === 'visible') {
        timer = window.setInterval(() => latestReload.current(), POLL_INTERVAL_MS);
      }
    };
    const stop = () => {
      if (timer !== null) {
        window.clearInterval(timer);
        timer = null;
      }
    };
    const onVisibilityChange = () => {
      if (document.visibilityState === 'visible') start();
      else stop();
    };
    start();
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      stop();
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, [active]);
}
