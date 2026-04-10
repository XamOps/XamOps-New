import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type { PerformanceMetrics } from '../types/aiops';

export function usePerformanceMonitoringData(period: string, provider?: string, model?: string) {
  const [data, setData] = useState<PerformanceMetrics | null>(null);
  const [loading, setLoading] = useState(false);

  const loadPerformanceMetrics = useCallback(async () => {
    setLoading(true);
    try {
      let url = `/api/xamops/aiops/performance?period=${period}`;
      if (provider) url += `&provider=${encodeURIComponent(provider)}`;
      if (model) url += `&model=${encodeURIComponent(model)}`;
      const result = await apiFetchJson<PerformanceMetrics>(url);
      setData(result);
    } catch {
      // silently fail
    } finally {
      setLoading(false);
    }
  }, [period, provider, model]);

  return { data, loading, loadPerformanceMetrics };
}
