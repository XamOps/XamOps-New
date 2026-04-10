import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type { InsightsSummary } from '../types/aiops';

export function useInsightsData(period: string) {
  const [data, setData] = useState<InsightsSummary | null>(null);
  const [loading, setLoading] = useState(false);

  const loadInsights = useCallback(async () => {
    setLoading(true);
    try {
      const result = await apiFetchJson<InsightsSummary>(
        `/api/xamops/aiops/insights?period=${period}`
      );
      setData(result);
    } catch {
      // silently fail
    } finally {
      setLoading(false);
    }
  }, [period]);

  return { data, loading, loadInsights };
}
