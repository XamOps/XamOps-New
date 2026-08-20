/**
 * useRightsizingData Hook
 *
 * Custom hook to fetch and manage rightsizing recommendations data
 * Handles both AWS and XamOps recommendations with separate caching
 */

import { useState, useEffect, useCallback, useRef } from 'react';

// AWS Recommendation type
export interface AWSRecommendation {
  resourceId: string;
  instanceName?: string;
  service: string;
  currentType: string;
  recommendedType: string;
  currentMonthlyCost: number;
  recommendedMonthlyCost: number;
  estimatedMonthlySavings: number;
  recommendationReason: string;
  region?: string;
}

// XamOps Recommendation type
export interface XamOpsRecommendation {
  instanceId: string;
  instanceName?: string;
  currentInstance: string;
  currentUtilization?: string;
  loadRange: string;
  intelRecommendation: string;
  amdRecommendation: string;
  projectedMaxUtil: string;
  approxCostSavings: string;
  reason: string;
  region?: string;
  /** Live monthly cost of the current instance in its own region, USD. */
  currentMonthlyCost?: number | null;
  intelMonthlyCost?: number | null;
  amdMonthlyCost?: number | null;
  /** Best realizable monthly saving in USD across both options. */
  estimatedMonthlySavings?: number;
  /** True when no saving could be priced — exclude from totals. */
  savingsUnknown?: boolean;
  /** Whether each option can be applied via stop / modify / start. */
  intelApplicable?: boolean;
  intelBlockedReason?: string | null;
  amdApplicable?: boolean;
  amdBlockedReason?: string | null;
  /** True when an option crosses x86 <-> arm64 and needs a rebuilt AMI. */
  requiresArchitectureMigration?: boolean;
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
  const abortControllerRef = useRef<AbortController | null>(null);

  // Load AWS recommendations with caching
  const loadAWSRecommendations = useCallback(
    async (forceRefresh: boolean = false, signal?: AbortSignal) => {
      if (!selectedAccountId) {
        setIsLoadingAWS(false);
        return;
      }

      const cacheKey = `rightsizingRecs-${selectedAccountId}`;

      if (forceRefresh) {
        sessionStorage.removeItem(cacheKey);
      } else {
        const cachedData = sessionStorage.getItem(cacheKey);
        if (cachedData) {
          try {
            setAwsRecommendations(JSON.parse(cachedData));
            setIsLoadingAWS(false);
            return;
          } catch (e) {
            console.error('[Rightsizing] Failed to parse cached AWS data:', e);
          }
        }
      }

      setIsLoadingAWS(true);
      try {
        const response = await fetch(
          `/api/xamops/rightsizing/recommendations?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`,
          { credentials: 'include', signal }
        );

        if (!response.ok) {
          const text = await response.text();
          throw new Error(`HTTP error! status: ${response.status}, body: ${text}`);
        }

        const data = await response.json();
        const recommendations = Array.isArray(data) ? data : [];
        setAwsRecommendations(recommendations);

        try {
          sessionStorage.setItem(cacheKey, JSON.stringify(recommendations));
        } catch (e) {
          console.error('[Rightsizing] Failed to cache AWS data:', e);
        }
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
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
    async (forceRefresh: boolean = false, signal?: AbortSignal) => {
      if (!selectedAccountId) {
        setIsLoadingXamOps(false);
        return;
      }

      const cacheKey = `xamopsRightsizing-${selectedAccountId}`;

      if (forceRefresh) {
        sessionStorage.removeItem(cacheKey);
      } else {
        const cachedData = sessionStorage.getItem(cacheKey);
        if (cachedData) {
          try {
            const parsed = JSON.parse(cachedData);
            if (Array.isArray(parsed) && parsed.length > 0) {
              setXamopsRecommendations(parsed);
              setIsLoadingXamOps(false);
              return;
            }
          } catch (e) {
            console.error('[Rightsizing] Failed to parse cached XamOps data:', e);
          }
        }
      }

      setIsLoadingXamOps(true);
      try {
        const response = await fetch(
          `/api/xamops/optimization/rightsizing/live?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`,
          { credentials: 'include', signal }
        );

        if (!response.ok) {
          const text = await response.text();
          throw new Error(`HTTP error! status: ${response.status}, body: ${text}`);
        }

        const data = await response.json();
        const recommendations = Array.isArray(data) ? data : [];
        setXamopsRecommendations(recommendations);

        try {
          if (recommendations.length > 0) {
            sessionStorage.setItem(cacheKey, JSON.stringify(recommendations));
          }
        } catch (e) {
          console.error('[Rightsizing] Failed to cache XamOps data:', e);
        }
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
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
    if (isRefreshing) return;

    // Cancel any in-flight requests
    abortControllerRef.current?.abort();
    abortControllerRef.current = new AbortController();
    const { signal } = abortControllerRef.current;

    setIsRefreshing(true);
    try {
      await Promise.all([
        loadAWSRecommendations(true, signal),
        loadXamOpsRecommendations(true, signal),
      ]);
    } finally {
      setIsRefreshing(false);
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
