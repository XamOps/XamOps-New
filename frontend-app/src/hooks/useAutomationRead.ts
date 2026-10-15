/**
 * Loads one cluster's endpoint-1 read for the Automation pages that only read it (Overview,
 * Policies): one `GET`, both views. No polling and no optimistic state; the next read is a user
 * action (`reload`). A read that answers after the page unmounted, or after the cluster changed, is
 * dropped.
 */

import { useCallback, useEffect, useState } from 'react';
import { fetchAutomationRead, type AutomationReadResult } from '../api/automationReadApi';

export interface UseAutomationReadArgs {
  clusterName: string | null;
  accountId: string | null;
  region: string | null;
}

export interface UseAutomationReadState {
  loading: boolean;
  /** null until the first read answers, or while the cluster, account or region is missing. */
  result: AutomationReadResult | null;
  reload: () => void;
}

export const useAutomationRead = ({ clusterName, accountId, region }: UseAutomationReadArgs): UseAutomationReadState => {
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<AutomationReadResult | null>(null);
  const [reloadToken, setReloadToken] = useState(0);

  useEffect(() => {
    if (!clusterName || !accountId || !region) {
      setResult(null);
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    fetchAutomationRead(clusterName, accountId, region)
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

  return { loading, result, reload };
};

export default useAutomationRead;
