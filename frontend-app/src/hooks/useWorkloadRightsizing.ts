/**
 * Loads the vertical rightsizer payload for one cluster and submits one recommendation at a time.
 *
 * Deliberately has no polling and no optimistic state: an apply is asynchronous, so the only honest
 * thing to show after submitting is what the server replied, and the next refresh is a user action.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { applyVerticalRecommendation, fetchVerticalEngine } from '../api/workloadRightsizingApi';
import { toApplyContainers } from '../api/workloadRightsizingAdapter';
import type { ApplyResult, VerticalReadResult, WorkloadView } from '../types/workloadRightsizing';

export interface UseVerticalRightsizerArgs {
  clusterName: string | null;
  accountId: string | null;
  region: string | null;
}

export interface UseVerticalRightsizerState {
  loading: boolean;
  result: VerticalReadResult | null;
  reload: () => void;
  /** Which workload id currently has an apply in flight, or null. */
  applyingId: string | null;
  /** The last reply per workload id. Never a fabricated success. */
  applyResults: Record<string, ApplyResult>;
  apply: (workload: WorkloadView, dryRun: boolean) => Promise<ApplyResult | null>;
}

export const useVerticalRightsizer = ({
  clusterName,
  accountId,
  region,
}: UseVerticalRightsizerArgs): UseVerticalRightsizerState => {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<VerticalReadResult | null>(null);
  const [applyingId, setApplyingId] = useState<string | null>(null);
  const [applyResults, setApplyResults] = useState<Record<string, ApplyResult>>({});
  const [reloadToken, setReloadToken] = useState(0);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (!clusterName || !accountId || !region) {
      setResult(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    fetchVerticalEngine(clusterName, accountId, region)
      .then((next) => {
        if (!cancelled) setResult(next);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [clusterName, accountId, region, reloadToken]);

  const reload = useCallback(() => setReloadToken((t) => t + 1), []);

  const apply = useCallback(
    async (workload: WorkloadView, dryRun: boolean): Promise<ApplyResult | null> => {
      if (!clusterName || !accountId || !region) return null;
      const containers = toApplyContainers(workload);
      if (containers.length === 0) return null;

      setApplyingId(workload.id);
      try {
        const reply = await applyVerticalRecommendation(clusterName, workload.ref, accountId, region, {
          containers,
          applyMethod: workload.policy?.applyMethod ?? 'IN_PLACE',
          dryRun,
        });
        if (mounted.current) {
          setApplyResults((prev) => ({ ...prev, [workload.id]: reply }));
        }
        return reply;
      } finally {
        if (mounted.current) setApplyingId(null);
      }
    },
    [clusterName, accountId, region],
  );

  return { loading, result, reload, applyingId, applyResults, apply };
};

export default useVerticalRightsizer;
