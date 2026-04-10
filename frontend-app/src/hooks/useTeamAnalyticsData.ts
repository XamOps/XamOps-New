import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type { TeamAnalyticsSummary } from '../types/aiops';

export function useTeamAnalyticsData(period: string, provider?: string) {
  const [data, setData] = useState<TeamAnalyticsSummary | null>(null);
  const [loading, setLoading] = useState(false);

  const loadTeamAnalytics = useCallback(async () => {
    setLoading(true);
    try {
      let url = `/api/xamops/aiops/team-analytics?period=${period}`;
      if (provider) url += `&provider=${encodeURIComponent(provider)}`;
      const result = await apiFetchJson<TeamAnalyticsSummary>(url);
      setData(result);
    } catch {
      // silently fail
    } finally {
      setLoading(false);
    }
  }, [period, provider]);

  return { data, loading, loadTeamAnalytics };
}
