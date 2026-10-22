/**
 * One cancellable Phase-2 read (usage, sparklines, HPA history, the activity summary), keyed by
 * what it asks for. No polling: the next read is a user action (`reload`) or a different key. When
 * the key changes or the page unmounts, the request in flight is aborted and its answer dropped, so
 * an answer for an older key is never shown under a newer one. A `null` key reads nothing.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import type { InsightReadResult } from '../types/automationInsights';

export interface InsightReadState<T> {
  /** True while the read for the current key runs. */
  loading: boolean;
  /** The current key's answer; null while it loads, or when the key is null. */
  result: InsightReadResult<T> | null;
  reload: () => void;
}

export function useInsightRead<T>(
  key: string | null,
  run: (signal: AbortSignal) => Promise<InsightReadResult<T>>,
): InsightReadState<T> {
  const runRef = useRef(run);
  runRef.current = run;
  const [state, setState] = useState<{ key: string | null; token: number; result: InsightReadResult<T> | null }>({
    key: null,
    token: -1,
    result: null,
  });
  const [token, setToken] = useState(0);

  useEffect(() => {
    if (key === null) return undefined;
    const controller = new AbortController();
    let live = true;
    runRef.current(controller.signal)
      .then((result) => {
        if (live) setState({ key, token, result });
      })
      .catch((e: unknown) => {
        if (live) setState({ key, token, result: { kind: 'error', message: e instanceof Error ? e.message : "XamOps couldn't read it." } });
      });
    return () => {
      live = false;
      controller.abort();
    };
  }, [key, token]);

  const reload = useCallback(() => setToken((t) => t + 1), []);
  const current = key !== null && state.key === key && state.token === token;
  return { loading: key !== null && !current, result: current ? state.result : null, reload };
}

export default useInsightRead;
