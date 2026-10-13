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
import {
  applyVerticalRecommendation,
  fetchVerticalEngine,
  setVerticalWorkloadMode,
} from '../api/verticalRightsizerApi';
import { isCallerLevelRefusal, toApplyContainers } from '../api/verticalRightsizerAdapter';
import type {
  ApplyResult,
  CallerRefusal,
  ModeResult,
  VerticalReadResult,
  WorkloadModeValue,
  WorkloadView,
} from '../types/verticalRightsizer';

export interface UseVerticalRightsizerArgs {
  clusterName: string | null;
  accountId: string | null;
  region: string | null;
}

export interface UseVerticalRightsizerState {
  loading: boolean;
  result: VerticalReadResult | null;
  reload: () => void;
  /**
   * The workload ids with an apply in flight. One per workload, not one for the page: a single slot
   * let whichever apply finished first re-enable a card whose own rollout was still running
   * (review-2 SF5).
   */
  applyingIds: ReadonlySet<string>;
  /** The last reply per workload id. Never a fabricated success. */
  applyResults: Record<string, ApplyResult>;
  /** The workload ids with a mode change in flight. */
  modeChangingIds: ReadonlySet<string>;
  /** The last mode-change reply per workload id. */
  modeResults: Record<string, ModeResult>;
  /** A caller-level refusal endpoint 2 or 3 answered since the last read, or null. */
  callerRefusal: CallerRefusal | null;
  apply: (workload: WorkloadView, dryRun: boolean) => Promise<ApplyResult | null>;
  /** Sets one workload's policy mode through endpoint 2 and reloads so endpoint 1 reflects it. */
  setMode: (workload: WorkloadView, mode: WorkloadModeValue) => Promise<ModeResult | null>;
}

export const useVerticalRightsizer = ({
  clusterName,
  accountId,
  region,
}: UseVerticalRightsizerArgs): UseVerticalRightsizerState => {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<VerticalReadResult | null>(null);
  const [applyingIds, setApplyingIds] = useState<ReadonlySet<string>>(() => new Set());
  const [applyResults, setApplyResults] = useState<Record<string, ApplyResult>>({});
  const [modeChangingIds, setModeChangingIds] = useState<ReadonlySet<string>>(() => new Set());
  const [modeResults, setModeResults] = useState<Record<string, ModeResult>>({});
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

      setApplyingIds((prev) => new Set(prev).add(workload.id));
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
        if (mounted.current) {
          setApplyingIds((prev) => {
            const next = new Set(prev);
            next.delete(workload.id);
            return next;
          });
        }
      }
    },
    [clusterName, accountId, region],
  );

  const setMode = useCallback(
    async (workload: WorkloadView, mode: WorkloadModeValue): Promise<ModeResult | null> => {
      if (!clusterName || !accountId || !region) return null;

      setModeChangingIds((prev) => new Set(prev).add(workload.id));
      try {
        const reply = await setVerticalWorkloadMode(clusterName, workload.ref, accountId, region, mode);
        if (mounted.current) {
          setModeResults((prev) => ({ ...prev, [workload.id]: reply }));
          // A caller-level refusal (demo, permission, read-only, account) is about the user, not the
          // row: grey every control and re-read the page's gates, exactly as a refused apply does.
          if (reply.kind === 'refused' && reply.code.kind === 'known' && isCallerLevelRefusal(reply.code)) {
            setCallerRefusal({ code: reply.code.code, detail: reply.detail });
          }
          // A saved mode changes what endpoint 1 reports (mode, policySource, lastAction), so read
          // again. A user-initiated read also clears a stale refusal once the gates are re-checked.
          if (reply.kind === 'saved') {
            clearRefusalOnRead.current = true;
          }
          setReloadToken((t) => t + 1);
        }
        return reply;
      } finally {
        if (mounted.current) {
          setModeChangingIds((prev) => {
            const next = new Set(prev);
            next.delete(workload.id);
            return next;
          });
        }
      }
    },
    [clusterName, accountId, region],
  );

  return {
    loading,
    result,
    reload,
    applyingIds,
    applyResults,
    modeChangingIds,
    modeResults,
    callerRefusal,
    apply,
    setMode,
  };
};

export default useVerticalRightsizer;
