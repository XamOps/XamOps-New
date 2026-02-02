/**
 * Custom hook for GCP Performance Insights data
 */

import { useState, useEffect, useCallback } from 'react';
import apiClient from '../utils/apiClient';
import { PerformanceInsight, PerformanceSummary } from '../types/gcpPerformance';

interface UseGcpPerformanceDataOptions {
  accountId: string | null;
  autoRefresh?: boolean;
  refreshInterval?: number; // in seconds
}

interface UseGcpPerformanceDataReturn {
  insights: PerformanceInsight[];
  summary: PerformanceSummary;
  loading: boolean;
  error: Error | null;
  refresh: () => void;
  archiveInsights: (insightIds: string[]) => Promise<boolean>;
}

export const useGcpPerformanceData = ({
  accountId,
  autoRefresh = false,
  refreshInterval = 60,
}: UseGcpPerformanceDataOptions): UseGcpPerformanceDataReturn => {
  const [insights, setInsights] = useState<PerformanceInsight[]>([]);
  const [summary, setSummary] = useState<PerformanceSummary>({
    performanceScore: 0,
    totalInsights: 0,
    critical: 0,
    warning: 0,
    weakWarning: 0,
  });
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<Error | null>(null);

  /**
   * Create a unique key for deduplication
   */
  const createInsightKey = (insight: PerformanceInsight, defaultAccountId: string): string => {
    const idPart = insight.resourceId || insight.id || '';
    return `${idPart}-${insight.insight || ''}-${insight.account || defaultAccountId}-${insight.region || ''}`;
  };

  /**
   * Deduplicate insights
   */
  const deduplicateInsights = useCallback((insightList: PerformanceInsight[], accountIdForKey: string): PerformanceInsight[] => {
    const seen = new Set<string>();
    const uniqueInsights: PerformanceInsight[] = [];

    if (Array.isArray(insightList)) {
      insightList.forEach(insight => {
        const key = createInsightKey(insight, accountIdForKey);
        if (!seen.has(key)) {
          seen.add(key);
          uniqueInsights.push(insight);
        }
      });
    }

    return uniqueInsights;
  }, []);

  /**
   * Load insights data
   */
  const loadInsights = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!accountId) {
        setLoading(false);
        return;
      }

      const cacheKey = `gcpPerformanceInsights-${accountId}`;
      const cachedData = sessionStorage.getItem(cacheKey);

      // Use cache if available and not forcing refresh
      if (cachedData && !forceRefresh) {
        try {
          const parsedData = JSON.parse(cachedData);
          setInsights(deduplicateInsights(parsedData, accountId));
          setLoading(false);
          return;
        } catch (err) {
          console.error('Failed to parse cached insights:', err);
        }
      }

      // Fetch from API
      try {
        // Only show loading if forcing refresh or if we don't have data
        if (forceRefresh || insights.length === 0) {
          setLoading(true);
        }
        setError(null);

        const data = await apiClient.get<PerformanceInsight[]>(
          `/api/xamops/gcp/performance-insights/insights?accountId=${accountId}&forceRefresh=${forceRefresh}`
        );

        const uniqueInsights = deduplicateInsights(data || [], accountId);
        setInsights(uniqueInsights);

        // Cache the data
        sessionStorage.setItem(cacheKey, JSON.stringify(uniqueInsights));
      } catch (err) {
        console.error('Failed to load GCP performance insights:', err);
        setError(err instanceof Error ? err : new Error('Failed to load insights'));
        // Keep previous insights on error to prevent UI from emptying
        if (insights.length === 0) {
          setInsights([]);
        }
      } finally {
        setLoading(false);
      }
    },
    [accountId, deduplicateInsights]
  );

  /**
   * Load summary data
   */
  const loadSummary = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!accountId) return;

      const cacheKey = `gcpPerformanceSummary-${accountId}`;
      const cachedData = sessionStorage.getItem(cacheKey);

      // Use cache if available and not forcing refresh
      if (cachedData && !forceRefresh) {
        try {
          const parsedData = JSON.parse(cachedData);
          setSummary(parsedData);
          return;
        } catch (err) {
          console.error('Failed to parse cached summary:', err);
        }
      }

      // Fetch from API
      try {
        const data = await apiClient.get<PerformanceSummary>(
          `/api/xamops/gcp/performance-insights/summary?accountId=${accountId}&forceRefresh=${forceRefresh}`
        );

        setSummary(data);

        // Cache the data
        sessionStorage.setItem(cacheKey, JSON.stringify(data));
      } catch (err) {
        console.error('Failed to load GCP performance summary:', err);
        // Don't reset summary on error to prevent UI flashing
      }
    },
    [accountId]
  );

  /**
   * Archive insights
   */
  const archiveInsights = useCallback(
    async (insightIds: string[]): Promise<boolean> => {
      if (insightIds.length === 0) return false;

      try {
        await apiClient.post('/api/xamops/gcp/performance-insights/bulk-archive', insightIds);

        // Remove archived insights from state
        setInsights(prevInsights =>
          prevInsights.filter(insight => !insightIds.includes(insight.id || createInsightKey(insight, accountId || '')))
        );

        // Refresh summary
        await loadSummary(true);

        return true;
      } catch (err) {
        console.error('Failed to archive insights:', err);
        throw err;
      }
    },
    [accountId, loadSummary]
  );

  /**
   * Refresh all data
   */
  const refresh = useCallback(() => {
    if (accountId) {
      // Clear cache
      sessionStorage.removeItem(`gcpPerformanceInsights-${accountId}`);
      sessionStorage.removeItem(`gcpPerformanceSummary-${accountId}`);

      // Reload data
      loadInsights(true);
      loadSummary(true);
    }
  }, [accountId, loadInsights, loadSummary]);

  /**
   * Initial load
   */
  useEffect(() => {
    if (accountId) {
      loadInsights(false);
      loadSummary(false);
    }
  }, [accountId, loadInsights, loadSummary]);

  /**
   * Auto-refresh interval
   */
  useEffect(() => {
    if (!autoRefresh || !accountId) return;

    const intervalId = setInterval(() => {
      loadInsights(true);
      loadSummary(true);
    }, refreshInterval * 1000);

    return () => clearInterval(intervalId);
  }, [autoRefresh, refreshInterval, accountId, loadInsights, loadSummary]);

  return {
    insights,
    summary,
    loading,
    error,
    refresh,
    archiveInsights,
  };
};
