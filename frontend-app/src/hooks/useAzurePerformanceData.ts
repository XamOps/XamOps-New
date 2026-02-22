import { useState, useEffect, useCallback, useRef } from 'react';
import type { AzurePerformanceData, AzurePerformanceStatus } from '../types/azurePerformance';

interface UseAzurePerformanceDataParams {
  selectedAccountId: string | null;
}

interface UseAzurePerformanceDataReturn {
  performanceData: AzurePerformanceData | null;
  isLoading: boolean;
  isRefreshing: boolean;
  loadPerformanceData: (forceRefresh?: boolean) => Promise<void>;
}

const CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

export const useAzurePerformanceData = ({
  selectedAccountId,
}: UseAzurePerformanceDataParams): UseAzurePerformanceDataReturn => {
  const [performanceData, setPerformanceData] = useState<AzurePerformanceData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

  const loadFromCache = useCallback((): AzurePerformanceData | null => {
    if (!selectedAccountId) return null;
    try {
      const cached = localStorage.getItem(`azure_performance_${selectedAccountId}`);
      const expiry = localStorage.getItem(`azure_performance_${selectedAccountId}_expiry`);
      if (cached && expiry && Date.now() <= parseInt(expiry)) {
        const data = JSON.parse(cached) as AzurePerformanceData;
        if (!data.status) return null; // stale pre-wrapper cache — re-fetch
        console.log('[Azure Performance] Loaded from cache: status:', data.status,
          'insights:', data.insights?.length, 'score:', data.performanceScore);
        return data;
      }
    } catch (err) {
      console.error('[Azure Performance] Cache read failed:', err);
    }
    return null;
  }, [selectedAccountId]);

  const saveToCache = useCallback((data: AzurePerformanceData) => {
    if (!selectedAccountId) return;
    try {
      localStorage.setItem(`azure_performance_${selectedAccountId}`, JSON.stringify(data));
      localStorage.setItem(
        `azure_performance_${selectedAccountId}_expiry`,
        (Date.now() + CACHE_DURATION).toString()
      );
    } catch (err) {
      console.error('[Azure Performance] Cache write failed:', err);
    }
  }, [selectedAccountId]);

  const clearCache = useCallback(() => {
    if (!selectedAccountId) return;
    localStorage.removeItem(`azure_performance_${selectedAccountId}`);
    localStorage.removeItem(`azure_performance_${selectedAccountId}_expiry`);
  }, [selectedAccountId]);

  const loadPerformanceData = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setPerformanceData(null);
        setIsLoading(false);
        return;
      }

      if (isFetchingRef.current && !forceRefresh) return;
      isFetchingRef.current = true;

      if (forceRefresh) {
        setIsRefreshing(true);
        setIsLoading(true);
        clearCache();
      } else {
        const cached = loadFromCache();
        if (cached) {
          setPerformanceData(cached);
          setIsLoading(false);
          isFetchingRef.current = false;
          return;
        }
        setIsLoading(true);
      }

      try {
        const url = `/api/xamops/azure/performance?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;
        console.log('[Azure Performance] Fetching:', url);

        const res = await fetch(url, {
          credentials: 'include',
          headers: { Accept: 'application/json' },
        });

        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const raw = await res.json();
        const data: AzurePerformanceData = {
          status: (raw.status as AzurePerformanceStatus) ?? 'API_ERROR',
          performanceScore: raw.performanceScore ?? 100,
          insights: Array.isArray(raw.insights) ? raw.insights : [],
        };

        console.log('[Azure Performance] Received: status:', data.status,
          'score:', data.performanceScore, 'insights:', data.insights.length);

        setPerformanceData(data);
        saveToCache(data);
      } catch (err) {
        console.error('[Azure Performance] Fetch failed:', err);
        if (!forceRefresh) {
          setPerformanceData({
            status: 'API_ERROR',
            performanceScore: 100,
            insights: [],
          });
        }
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
        isFetchingRef.current = false;
      }
    },
    [selectedAccountId, loadFromCache, saveToCache, clearCache]
  );

  useEffect(() => {
    if (selectedAccountId) {
      const isDifferent = previousAccountRef.current !== selectedAccountId;
      if (isDifferent || !initializedRef.current) {
        previousAccountRef.current = selectedAccountId;
        initializedRef.current = true;
        loadPerformanceData(false);
      }
    } else {
      setPerformanceData(null);
      setIsLoading(false);
    }
  }, [selectedAccountId, loadPerformanceData]);

  return { performanceData, isLoading, isRefreshing, loadPerformanceData };
};
