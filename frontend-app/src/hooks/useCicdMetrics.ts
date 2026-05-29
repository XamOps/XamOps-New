import { useState, useEffect, useCallback } from 'react';
import { CicdMetrics, TimeRange } from '../types/cicdPipelines';
import apiClient from '../utils/apiClient';

interface UseCicdMetricsReturn {
  metrics: CicdMetrics | null;
  isLoading: boolean;
  error: string | null;
  days: TimeRange;
  setDays: (d: TimeRange) => void;
  refetch: () => Promise<void>;
}

const EMPTY_METRICS: CicdMetrics = {
  successRate: 0,
  totalRuns: 0,
  failureCount: 0,
  avgDurationSeconds: 0,
  prevSuccessRate: 0,
  prevTotalRuns: 0,
  prevFailureCount: 0,
  prevAvgDurationSeconds: 0,
  dora: null,
  dailyStats: [],
  topFailingStages: [],
  topFailingWorkflows: [],
};

export function useCicdMetrics(provider: 'github' | 'jenkins'): UseCicdMetricsReturn {
  const [metrics, setMetrics] = useState<CicdMetrics | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [days, setDays] = useState<TimeRange>(7);

  const fetchMetrics = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const data = await apiClient.get<CicdMetrics>(
        `/api/cicd/${provider}/metrics?days=${days}`
      );
      const isValid = data && typeof data === 'object' && !Array.isArray(data) && typeof (data as any).successRate === 'number';
      setMetrics(isValid ? data : EMPTY_METRICS);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : 'Failed to load metrics';
      setError(msg);
      setMetrics(EMPTY_METRICS);
    } finally {
      setIsLoading(false);
    }
  }, [provider, days]);

  useEffect(() => {
    fetchMetrics();
  }, [fetchMetrics]);

  return { metrics, isLoading, error, days, setDays, refetch: fetchMetrics };
}
