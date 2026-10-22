/**
 * Loads one cluster's HPA page and submits one HPA tune at a time.
 *
 * Mirrors {@link ./useVerticalRightsizer.ts}: no polling, no optimistic state — an apply is
 * asynchronous, so the only honest thing to show after submitting is what the server replied, and
 * the next refresh is a user action. A caller-level refusal (demo, permission, read-only
 * connection, account) is kept as `callerRefusal` to grey every Apply, and the page is re-read so it
 * shows the server's current gates; it clears only when the user presses Refresh and that read has
 * evaluated the caller's write gates.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { applyHpaRecommendation, fetchHpaPage } from '../api/hpaApi';
import { hpaApplyGate, isCallerLevelRefusal } from '../api/hpaAdapter';
import { useK8sCapabilities } from './useK8sCapabilities';
import type { HpaApplyResult, HpaCallerRefusal, HpaReadResult, HpaWorkloadView } from '../types/hpaAutomation';

export interface UseHpaAutomationArgs {
  clusterName: string | null;
  accountId: string | null;
  region: string | null;
}

export interface UseHpaAutomationState {
  loading: boolean;
  result: HpaReadResult | null;
  reload: () => void;
  /** The workload ids with an apply in flight. */
  applyingIds: ReadonlySet<string>;
  /** The last reply per workload id. Never a fabricated success. */
  applyResults: Record<string, HpaApplyResult>;
  /** A caller-level refusal the apply endpoint answered since the last read, or null. */
  callerRefusal: HpaCallerRefusal | null;
  apply: (workload: HpaWorkloadView, dryRun: boolean) => Promise<HpaApplyResult | null>;
}

export const useHpaAutomation = ({ clusterName, accountId, region }: UseHpaAutomationArgs): UseHpaAutomationState => {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<HpaReadResult | null>(null);
  const [applyingIds, setApplyingIds] = useState<ReadonlySet<string>>(() => new Set());
  const [applyResults, setApplyResults] = useState<Record<string, HpaApplyResult>>({});
  const [callerRefusal, setCallerRefusal] = useState<HpaCallerRefusal | null>(null);
  const [reloadToken, setReloadToken] = useState(0);
  const clearRefusalOnRead = useRef(false);
  const mounted = useRef(true);
  const { can } = useK8sCapabilities();
  const hpaApplyReady = can('hpa.apply', { cluster: clusterName }).allowed;

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

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
    fetchHpaPage(clusterName, accountId, region)
      .then((next) => {
        if (!cancelled) {
          setResult(next);
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
    async (workload: HpaWorkloadView, dryRun: boolean): Promise<HpaApplyResult | null> => {
      if (!clusterName || !accountId || !region) return null;
      if (result?.kind !== 'ok') return null;
      const gate = hpaApplyGate(result.view, workload, dryRun, callerRefusal, hpaApplyReady);
      if (!gate.enabled || gate.changes === null) return null;

      setApplyingIds((prev) => new Set(prev).add(workload.id));
      try {
        const reply = await applyHpaRecommendation(clusterName, workload.ref, accountId, region, gate.changes);
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
    [clusterName, accountId, region, result, callerRefusal, hpaApplyReady],
  );

  return { loading, result, reload, applyingIds, applyResults, callerRefusal, apply };
};

export default useHpaAutomation;
