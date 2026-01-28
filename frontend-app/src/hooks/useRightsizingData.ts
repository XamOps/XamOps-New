/**
 * useRightsizingData Hook
 *
 * Custom hook to fetch and manage rightsizing recommendations data
 * Handles both AWS and XamOps recommendations with separate caching
 */

import { useState, useEffect, useCallback } from 'react';

// AWS Recommendation type
export interface AWSRecommendation {
  resourceId: string;
  service: string;
  currentType: string;
  recommendedType: string;
  currentMonthlyCost: number;
  recommendedMonthlyCost: number;
  estimatedMonthlySavings: number;
  recommendationReason: string;
}

// XamOps Recommendation type
export interface XamOpsRecommendation {
  instanceId: string;
  currentInstance: string;
  currentUtilization?: string;
  loadRange: string;
  intelRecommendation: string;
  amdRecommendation: string;
  projectedMaxUtil: string;
  approxCostSavings: string;
  reason: string;
}

interface UseRightsizingDataOptions {
  selectedAccountId: string | null;
}

interface UseRightsizingDataReturn {
  awsRecommendations: AWSRecommendation[];
  xamopsRecommendations: XamOpsRecommendation[];
  isLoadingAWS: boolean;
  isLoadingXamOps: boolean;
  isRefreshing: boolean;
  error: Error | null;
  refreshAll: () => Promise<void>;
}

export const useRightsizingData = ({
  selectedAccountId,
}: UseRightsizingDataOptions): UseRightsizingDataReturn => {
  const [awsRecommendations, setAwsRecommendations] = useState<AWSRecommendation[]>([]);
  const [xamopsRecommendations, setXamopsRecommendations] = useState<XamOpsRecommendation[]>([]);
  const [isLoadingAWS, setIsLoadingAWS] = useState<boolean>(true);
  const [isLoadingXamOps, setIsLoadingXamOps] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);

  // Load AWS recommendations with caching
  const loadAWSRecommendations = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        console.log('[Rightsizing] No account selected, skipping AWS recommendations load.');
        setIsLoadingAWS(false);
        return;
      }

      setIsLoadingAWS(true);

      try {
        // Check cache first (only if not forcing refresh)
        const cacheKey = `rightsizingRecs-${selectedAccountId}`;
        if (!forceRefresh) {
          const cachedData = sessionStorage.getItem(cacheKey);
          if (cachedData) {
            try {
              const parsed = JSON.parse(cachedData);
              setAwsRecommendations(parsed);
              setIsLoadingAWS(false);
              console.log('[Rightsizing] Loaded cached AWS recommendations:', parsed.length);
              return;
            } catch (e) {
              console.error('[Rightsizing] Failed to parse cached AWS data:', e);
            }
          }
        }

        // Clear cache if forcing refresh
        if (forceRefresh) {
          sessionStorage.removeItem(cacheKey);
        }

        // Fetch fresh data
        console.log('[Rightsizing] Fetching AWS recommendations for account:', selectedAccountId);

        const response = await fetch(
          `/api/xamops/rightsizing/recommendations?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`,
          {
            credentials: 'include',
          }
        );

        if (!response.ok) {
          const text = await response.text();
          throw new Error(`HTTP error! status: ${response.status}, body: ${text}`);
        }

        const data = await response.json();
        const recommendations = Array.isArray(data) ? data : [];

        setAwsRecommendations(recommendations);
        console.log('[Rightsizing] Loaded', recommendations.length, 'AWS recommendations');

        // Cache the data
        try {
          sessionStorage.setItem(cacheKey, JSON.stringify(recommendations));
        } catch (e) {
          console.error('[Rightsizing] Failed to cache AWS data:', e);
        }
      } catch (err) {
        console.error('[Rightsizing] Failed to load AWS recommendations:', err);
        setError(err instanceof Error ? err : new Error('Failed to load AWS recommendations'));
        setAwsRecommendations([]);
      } finally {
        setIsLoadingAWS(false);
      }
    },
    [selectedAccountId]
  );

  // Load XamOps recommendations with caching
  const loadXamOpsRecommendations = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        console.log('[Rightsizing] No account selected, skipping XamOps recommendations load.');
        setIsLoadingXamOps(false);
        return;
      }

      setIsLoadingXamOps(true);

      try {
        // Check cache first (only if not forcing refresh)
        const cacheKey = `xamopsRightsizing-${selectedAccountId}`;
        if (!forceRefresh) {
          const cachedData = sessionStorage.getItem(cacheKey);
          if (cachedData) {
            try {
              const parsed = JSON.parse(cachedData);
              if (Array.isArray(parsed) && parsed.length > 0) {
                setXamopsRecommendations(parsed);
                setIsLoadingXamOps(false);
                console.log('[Rightsizing] Loaded cached XamOps recommendations:', parsed.length);
                return;
              }
            } catch (e) {
              console.error('[Rightsizing] Failed to parse cached XamOps data:', e);
            }
          }
        }

        // Clear cache if forcing refresh
        if (forceRefresh) {
          sessionStorage.removeItem(cacheKey);
        }

        // Fetch fresh data
        console.log('[Rightsizing] Fetching XamOps recommendations for account:', selectedAccountId);

        const response = await fetch(
          `/api/xamops/optimization/rightsizing/live?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`,
          {
            credentials: 'include',
          }
        );

        if (!response.ok) {
          const text = await response.text();
          throw new Error(`HTTP error! status: ${response.status}, body: ${text}`);
        }

        const data = await response.json();
        const recommendations = Array.isArray(data) ? data : [];

        setXamopsRecommendations(recommendations);
        console.log('[Rightsizing] Loaded', recommendations.length, 'XamOps recommendations');

        // Cache the data
        try {
          if (recommendations.length > 0) {
            sessionStorage.setItem(cacheKey, JSON.stringify(recommendations));
          }
        } catch (e) {
          console.error('[Rightsizing] Failed to cache XamOps data:', e);
        }
      } catch (err) {
        console.error('[Rightsizing] Failed to load XamOps recommendations:', err);
        setError(err instanceof Error ? err : new Error('Failed to load XamOps recommendations'));
        setXamopsRecommendations([]);
      } finally {
        setIsLoadingXamOps(false);
      }
    },
    [selectedAccountId]
  );

  // Refresh all recommendations
  const refreshAll = useCallback(async () => {
    if (isRefreshing) {
      console.warn('[Rightsizing] Refresh already in progress');
      return;
    }

    console.log('[Rightsizing] Refreshing all recommendations...');
    setIsRefreshing(true);

    try {
      await Promise.all([
        loadAWSRecommendations(true),
        loadXamOpsRecommendations(true)
      ]);
      console.log('[Rightsizing] All recommendations refreshed');
    } finally {
      // Add a small delay to show the refreshing state
      setTimeout(() => {
        setIsRefreshing(false);
      }, 1000);
    }
  }, [isRefreshing, loadAWSRecommendations, loadXamOpsRecommendations]);

  // Initial load
  useEffect(() => {
    if (selectedAccountId) {
      loadAWSRecommendations(false);
      loadXamOpsRecommendations(false);
    }
  }, [selectedAccountId]); // Only re-run when selectedAccountId changes

  return {
    awsRecommendations,
    xamopsRecommendations,
    isLoadingAWS,
    isLoadingXamOps,
    isRefreshing,
    error,
    refreshAll,
  };
};
