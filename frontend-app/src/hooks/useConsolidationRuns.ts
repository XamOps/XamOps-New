/**
 * The consolidation controller's run history for Automation › Consolidation
 * (`GET /api/eks/autoscaling/{cluster}/consolidation-runs`), realized savings priced by the cost
 * engine. Read-only: the page that uses this has no controls. No polling; a read that answers after
 * the page unmounted, or after the cluster changed, is dropped. `retry` reads it again.
 *
 * The acting counterpart of {@link useConsolidationPlan}: identical caching-hook contract, pointed
 * at the runs endpoint.
 */
import { useCallback, useEffect, useState } from 'react';
import { getConsolidationRuns } from '../api/k8sAutomationApi';
import type { ConsolidationRunResponse } from '../types/k8sAutomation';
import type { ReadState } from './useNodeAutomationRead';

export interface ConsolidationRunsRead {
  runs: ReadState<ConsolidationRunResponse>;
  retry: () => void;
}

const messageOf = (e: unknown): string => (e instanceof Error && e.message ? e.message : "XamOps couldn't read it.");

export function useConsolidationRuns(cluster: string | null, accountId: string | null, region: string | null): ConsolidationRunsRead {
  const [runs, setRuns] = useState<ReadState<ConsolidationRunResponse>>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!cluster || !accountId) {
      setRuns({ kind: 'error', message: 'No cluster or account was given.' });
      return;
    }
    const controller = new AbortController();
    let live = true;
    setRuns({ kind: 'loading' });
    getConsolidationRuns({ cluster, accountId, region: region ?? undefined }, { signal: controller.signal })
      .then((data) => {
        if (live) setRuns({ kind: 'ok', data });
      })
      .catch((e: unknown) => {
        if (!live || (e instanceof DOMException && e.name === 'AbortError')) return;
        setRuns({ kind: 'error', message: messageOf(e) });
      });
    return () => {
      live = false;
      controller.abort();
    };
  }, [cluster, accountId, region, attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { runs, retry };
}

export default useConsolidationRuns;
