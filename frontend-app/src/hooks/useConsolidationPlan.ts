/**
 * The automation agent's look-only consolidation plan for Automation › Consolidation
 * (`GET /api/eks/autoscaling/{cluster}/consolidation-plan`), priced by the cost engine. Read-only:
 * the page that uses this has no controls. No polling; a read that answers after the page
 * unmounted, or after the cluster changed, is dropped. `retry` reads it again.
 */
import { useCallback, useEffect, useState } from 'react';
import { getConsolidationPlan } from '../api/k8sAutomationApi';
import type { ConsolidationPlanResponse } from '../types/k8sAutomation';
import type { ReadState } from './useNodeAutomationRead';

export interface ConsolidationPlanRead {
  plan: ReadState<ConsolidationPlanResponse>;
  retry: () => void;
}

const messageOf = (e: unknown): string => (e instanceof Error && e.message ? e.message : "XamOps couldn't read it.");

export function useConsolidationPlan(cluster: string | null, accountId: string | null, region: string | null): ConsolidationPlanRead {
  const [plan, setPlan] = useState<ReadState<ConsolidationPlanResponse>>({ kind: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    if (!cluster || !accountId) {
      setPlan({ kind: 'error', message: 'No cluster or account was given.' });
      return;
    }
    const controller = new AbortController();
    let live = true;
    setPlan({ kind: 'loading' });
    getConsolidationPlan({ cluster, accountId, region: region ?? undefined }, { signal: controller.signal })
      .then((data) => {
        if (live) setPlan({ kind: 'ok', data });
      })
      .catch((e: unknown) => {
        if (!live || (e instanceof DOMException && e.name === 'AbortError')) return;
        setPlan({ kind: 'error', message: messageOf(e) });
      });
    return () => {
      live = false;
      controller.abort();
    };
  }, [cluster, accountId, region, attempt]);

  const retry = useCallback(() => setAttempt((n) => n + 1), []);
  return { plan, retry };
}

export default useConsolidationPlan;
