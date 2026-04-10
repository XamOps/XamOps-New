import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type { ComputeMetricsSummary } from '../types/aiops';

export function useComputeMetricsData(period: string, provider: string) {
  const [data, setData] = useState<ComputeMetricsSummary | null>(null);
  const [loading, setLoading] = useState(false);

  const loadComputeMetrics = useCallback(async () => {
    setLoading(true);
    try {
      let url = `/api/xamops/aiops/compute-metrics?period=${period}`;
      if (provider) url += `&provider=${encodeURIComponent(provider)}`;
      const result = await apiFetchJson<ComputeMetricsSummary>(url);
      setData(result);
    } catch (err) {
      console.error('Failed to load compute metrics', err);
    } finally {
      setLoading(false);
    }
  }, [period, provider]);

  return { data, loading, loadComputeMetrics };
}
