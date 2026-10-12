/**
 * Loads the vertical rightsizer payload for one cluster and submits one recommendation at a time.
 *
 * Deliberately has no polling and no optimistic state: an apply is asynchronous, so the only honest
 * thing to show after submitting is what the server replied, and the next refresh is a user action.
 *
 * The one exception is a caller-level refusal (demo, permission, read-only connection, account):
 * it is about the user, not the workload, so it is kept as `callerRefusal` to grey every Apply on
 * the page, and the payload is read again so the page shows the server's current gates. It stays
 * until the user presses Refresh and that read has evaluated the caller's write gates.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { applyVerticalRecommendation, fetchVerticalEngine } from '../api/verticalRightsizerApi';
import { isCallerLevelRefusal, toApplyContainers } from '../api/verticalRightsizerAdapter';
import type { ApplyResult, CallerRefusal, VerticalReadResult, WorkloadView } from '../types/verticalRightsizer';

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
  /** A caller-level refusal endpoint 3 answered since the last read, or null. */
  callerRefusal: CallerRefusal | null;
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
  const [callerRefusal, setCallerRefusal] = useState<CallerRefusal | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  /** Set by a user's Refresh: the next read that checked the caller's gates replaces the refusal. */
  const clearRefusalOnRead = useRef(false);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  // A refusal is about this cluster's account and this caller; another cluster is another question.
  useEffect(() => {
    setCallerRefusal(null);
  }, [clusterName, accountId, region]);

  useEffect(() => {
    if (!clusterName || !accountId || !region) {
      setResult(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    fetchVerticalEngine(clusterName, accountId, region)
      .then((next) => {
        if (!cancelled) {
          setResult(next);
          // Only a read the user asked for, from a server that evaluated the caller's write gates,
          // replaces a refusal endpoint 3 has already answered. The automatic re-read after a
          // refusal refreshes the page's gates but keeps every Apply greyed: a server that said
          // "permitted" on endpoint 1 and then refused on endpoint 3 would only refuse again.
          if (clearRefusalOnRead.current && next.kind === 'ok' && next.view.writeGatesChecked) {
            clearRefusalOnRead.current = false;
            setCallerRefusal(null);
          }
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [clusterName, accountId, region, reloadToken]);

  const reload = useCallback(() => {
    clearRefusalOnRead.current = true;
    setReloadToken((t) => t + 1);
  }, []);

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
          if (reply.kind === 'refused' && reply.code.kind === 'known' && isCallerLevelRefusal(reply.code)) {
            setCallerRefusal({ code: reply.code.code, detail: reply.detail });
            setReloadToken((t) => t + 1);
          }
        }
        return reply;
      } finally {
        if (mounted.current) setApplyingId(null);
      }
    },
    [clusterName, accountId, region],
  );

  return { loading, result, reload, applyingId, applyResults, callerRefusal, apply };
};

export default useVerticalRightsizer;
