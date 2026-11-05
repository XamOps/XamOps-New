/**
 * useEksClusterCost — reads the real AWS $ for a cluster from the vertical pricing engine
 * (GET /api/eks/automation/vertical/{cluster}), the same already-authorised endpoint the
 * Automation tab proves. It does NOT re-trigger the dashboard fetch (design Decision 5).
 *
 * Guard, URL encoding, and error logging mirror useEksDashboard. Money fields are passed through
 * untouched: the engine emits null (never 0) for an unavailable figure and the UI renders a dash.
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import {
  VerticalCostSummary,
  VerticalEngineResponse,
  VerticalWorkload,
} from '../types/eksTypes';

interface UseEksClusterCostParams {
  clusterName: string | null;
  accountId: string | null;
  region: string;
}

export interface UseEksClusterCostReturn {
  cost: VerticalCostSummary | null;
  workloads: VerticalWorkload[];
  engine: VerticalEngineResponse['engine'] | undefined;
  loading: boolean;
  error: string | null;
}

export const useEksClusterCost = ({
  clusterName,
  accountId,
  region,
}: UseEksClusterCostParams): UseEksClusterCostReturn => {
  const [cost, setCost] = useState<VerticalCostSummary | null>(null);
  const [workloads, setWorkloads] = useState<VerticalWorkload[]>([]);
  const [engine, setEngine] = useState<VerticalEngineResponse['engine'] | undefined>(undefined);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // In-hook memo keyed on the three params (consistent with useEksDashboard); no new cache context.
  const lastKeyRef = useRef<string | null>(null);

  // Per-cluster isolation (FEAT-004). The token is the guarantee that a late response from a
  // previous cluster never lands on the current one; the AbortSignal is best-effort cancellation.
  const requestSeq = useRef(0);
  const abortRef = useRef<AbortController | null>(null);

  const fetchCost = useCallback(async () => {
    // Verbatim guard from useEksDashboard.fetchDashboard: do not fire until both params exist.
    if (!accountId || !clusterName) return;

    const key = `${clusterName}|${accountId}|${region}`;
    if (lastKeyRef.current === key) return;
    lastKeyRef.current = key;

    // Claim a monotonic token and abort any in-flight fetch for the previous key.
    const token = ++requestSeq.current;
    abortRef.current?.abort();
    const controller = new AbortController();
    abortRef.current = controller;
    const isStale = () => token !== requestSeq.current;

    setLoading(true);
    setError(null);

    try {
      const data = await apiClient.get<VerticalEngineResponse>(
        `/api/eks/automation/vertical/${encodeURIComponent(clusterName)}` +
          `?accountId=${encodeURIComponent(accountId)}&region=${encodeURIComponent(region)}`,
        { signal: controller.signal }
      );
      // Drop a response that belongs to a previous cluster/account/region.
      if (isStale()) return;
      setCost(data.cost ?? null);
      setWorkloads(data.workloads ?? []);
      setEngine(data.engine);
    } catch (err: any) {
      // A stale or aborted request must not surface its error on the current cluster.
      if (isStale()) return;
      if (err?.name === 'AbortError') return;
      console.error('Error fetching cluster cost:', err);
      // Reset the memo key so a remount re-attempts the failed fetch.
      lastKeyRef.current = null;
      setError(err?.message ?? 'Failed to load cluster cost');
      setCost(null);
      setWorkloads([]);
      setEngine(undefined);
    } finally {
      if (!isStale()) setLoading(false);
    }
  }, [accountId, clusterName, region]);

  // Reset-on-key-change (FEAT-004): invalidate any in-flight request and clear the view state so no
  // panel shows the previous cluster's cost/workloads/engine while the next fetch is in flight.
  useEffect(() => {
    requestSeq.current += 1;
    abortRef.current?.abort();
    lastKeyRef.current = null;
    setCost(null);
    setWorkloads([]);
    setEngine(undefined);
    setError(null);
    setLoading(true);
  }, [clusterName, accountId, region]);

  // Abort any in-flight request on unmount.
  useEffect(() => () => {
    requestSeq.current += 1;
    abortRef.current?.abort();
  }, []);

  useEffect(() => {
    fetchCost();
  }, [fetchCost]);

  return { cost, workloads, engine, loading, error };
};

export default useEksClusterCost;
