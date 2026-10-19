/**
 * Loads one cluster's RCA (`GET /api/eks/automation/vertical/{cluster}/rca`) for the Activity page's
 * RootCausePanel (FEAT-002). Mirrors `useAutomationRead`: one `GET`, no polling, the next read is a
 * user action (`reload`). A read that answers after the page unmounted, or after the cluster, account
 * or region changed, is dropped.
 */

import { useCallback, useEffect, useState } from 'react';
import { fetchRca, type RcaResult } from '../api/rcaApi';

export interface UseRcaArgs {
  clusterName: string | null;
  accountId: string | null;
  region: string | null;
  /** Optional window hint; the server clamps it to `[1,168]`. */
  windowHours?: number;
}

export interface UseRcaState {
  loading: boolean;
  /** null until the first read answers, or while the cluster, account or region is missing. */
  result: RcaResult | null;
  reload: () => void;
}

export const useRca = ({ clusterName, accountId, region, windowHours }: UseRcaArgs): UseRcaState => {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<RcaResult | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!clusterName || !accountId || !region) {
      setResult(null);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    fetchRca(clusterName, accountId, region, windowHours)
      .then((next) => {
        if (!cancelled) setResult(next);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [clusterName, accountId, region, windowHours, reloadToken]);

  const reload = useCallback(() => setReloadToken((t) => t + 1), []);

  return { loading, result, reload };
};

export default useRca;
