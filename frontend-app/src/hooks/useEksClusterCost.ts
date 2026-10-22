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

  const fetchCost = useCallback(async () => {
    // Verbatim guard from useEksDashboard.fetchDashboard: do not fire until both params exist.
    if (!accountId || !clusterName) return;

    const key = `${clusterName}|${accountId}|${region}`;
    if (lastKeyRef.current === key) return;
    lastKeyRef.current = key;

    setLoading(true);
    setError(null);

    try {
      const data = await apiClient.get<VerticalEngineResponse>(
        `/api/eks/automation/vertical/${encodeURIComponent(clusterName)}` +
          `?accountId=${encodeURIComponent(accountId)}&region=${encodeURIComponent(region)}`
      );
      setCost(data.cost ?? null);
      setWorkloads(data.workloads ?? []);
      setEngine(data.engine);
    } catch (err: any) {
      console.error('Error fetching cluster cost:', err);
      // Reset the memo key so a remount re-attempts the failed fetch.
      lastKeyRef.current = null;
      setError(err?.message ?? 'Failed to load cluster cost');
      setCost(null);
      setWorkloads([]);
      setEngine(undefined);
    } finally {
      setLoading(false);
    }
  }, [accountId, clusterName, region]);

  useEffect(() => {
    fetchCost();
  }, [fetchCost]);

  return { cost, workloads, engine, loading, error };
};

export default useEksClusterCost;
