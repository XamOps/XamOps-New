import { useState, useEffect, useCallback, useRef } from 'react';
import type { AzureRightsizingRecommendation, AzureRightsizingResponse, RightsizingStatus } from '../types/azureRightsizing';

interface UseAzureRightsizingDataParams {
  selectedAccountId: string | null;
}

interface UseAzureRightsizingDataReturn {
  recommendations: AzureRightsizingRecommendation[];
  status: RightsizingStatus | null;
  isLoading: boolean;
  isRefreshing: boolean;
  loadRecommendations: (forceRefresh?: boolean) => Promise<void>;
}

const CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

export const useAzureRightsizingData = ({
  selectedAccountId,
}: UseAzureRightsizingDataParams): UseAzureRightsizingDataReturn => {
  const [recommendations, setRecommendations] = useState<AzureRightsizingRecommendation[]>([]);
  const [status, setStatus] = useState<RightsizingStatus | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

  const loadFromCache = useCallback((): AzureRightsizingResponse | null => {
    if (!selectedAccountId) return null;
    try {
      const cached = localStorage.getItem(`azure_rightsizing_${selectedAccountId}`);
      const expiry = localStorage.getItem(`azure_rightsizing_${selectedAccountId}_expiry`);
      if (cached && expiry && Date.now() <= parseInt(expiry)) {
        const data = JSON.parse(cached) as AzureRightsizingResponse;
        if (!data.status) return null; // stale pre-wrapper cache — re-fetch
        console.log('[Azure Rightsizing] Loaded from cache:', data.recommendations?.length, 'items, status:', data.status);
        return data;
      }
    } catch (err) {
      console.error('[Azure Rightsizing] Cache read failed:', err);
    }
    return null;
  }, [selectedAccountId]);

  const saveToCache = useCallback((data: AzureRightsizingResponse) => {
    if (!selectedAccountId) return;
    try {
      localStorage.setItem(`azure_rightsizing_${selectedAccountId}`, JSON.stringify(data));
      localStorage.setItem(
        `azure_rightsizing_${selectedAccountId}_expiry`,
        (Date.now() + CACHE_DURATION).toString()
      );
    } catch (err) {
      console.error('[Azure Rightsizing] Cache write failed:', err);
    }
  }, [selectedAccountId]);

  const clearCache = useCallback(() => {
    if (!selectedAccountId) return;
    localStorage.removeItem(`azure_rightsizing_${selectedAccountId}`);
    localStorage.removeItem(`azure_rightsizing_${selectedAccountId}_expiry`);
  }, [selectedAccountId]);

  const loadRecommendations = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setRecommendations([]);
        setStatus(null);
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
          setRecommendations(cached.recommendations ?? []);
          setStatus(cached.status ?? null);
          setIsLoading(false);
          isFetchingRef.current = false;
          return;
        }
        setIsLoading(true);
      }

      try {
        const url = `/api/xamops/azure/rightsizing/recommendations?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;
        console.log('[Azure Rightsizing] Fetching:', url);

        const res = await fetch(url, {
          credentials: 'include',
          headers: { Accept: 'application/json' },
        });

        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const data: AzureRightsizingResponse = await res.json();
        const recs = Array.isArray(data.recommendations) ? data.recommendations : [];
        const responseStatus: RightsizingStatus = data.status ?? 'API_ERROR';

        console.log('[Azure Rightsizing] Received:', recs.length, 'recommendations, status:', responseStatus);

        setRecommendations(recs);
        setStatus(responseStatus);
        saveToCache({ status: responseStatus, recommendations: recs });
      } catch (err) {
        console.error('[Azure Rightsizing] Fetch failed:', err);
        if (!forceRefresh) {
          setRecommendations([]);
          setStatus('API_ERROR');
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
        loadRecommendations(false);
      }
    } else {
      setRecommendations([]);
      setStatus(null);
      setIsLoading(false);
    }
  }, [selectedAccountId, loadRecommendations]);

  return { recommendations, status, isLoading, isRefreshing, loadRecommendations };
};
