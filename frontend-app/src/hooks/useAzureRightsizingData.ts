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
  /** Set when a Refresh failed while earlier results stay on screen. */
  refreshError: string | null;
  loadRecommendations: (forceRefresh?: boolean) => Promise<void>;
}

const CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

/** Only real answers go in the browser cache; an error stored for 24 hours outlived the outage. */
const isCacheable = (status: RightsizingStatus) => status === 'OK' || status === 'NO_DATA';

export const useAzureRightsizingData = ({
  selectedAccountId,
}: UseAzureRightsizingDataParams): UseAzureRightsizingDataReturn => {
  const [recommendations, setRecommendations] = useState<AzureRightsizingRecommendation[]>([]);
  const [status, setStatus] = useState<RightsizingStatus | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [refreshError, setRefreshError] = useState<string | null>(null);

  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);
  // Only the latest request may write state. A load in flight used to block the load for a newly
  // selected subscription, and then the old subscription's response filled the page.
  const requestSeq = useRef(0);

  const loadFromCache = useCallback((): AzureRightsizingResponse | null => {
    if (!selectedAccountId) return null;
    try {
      const cached = localStorage.getItem(`azure_rightsizing_${selectedAccountId}`);
      const expiry = localStorage.getItem(`azure_rightsizing_${selectedAccountId}_expiry`);
      if (cached && expiry && Date.now() <= parseInt(expiry)) {
        const data = JSON.parse(cached) as AzureRightsizingResponse;
        if (!data.status || !isCacheable(data.status)) return null; // stale or error entry — re-fetch
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
      const requestId = ++requestSeq.current;
      if (!selectedAccountId) {
        setRecommendations([]);
        setStatus(null);
        setIsLoading(false);
        return;
      }

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
        if (requestId !== requestSeq.current) return;
        const recs = Array.isArray(data.recommendations) ? data.recommendations : [];
        const responseStatus: RightsizingStatus = data.status ?? 'API_ERROR';

        console.log('[Azure Rightsizing] Received:', recs.length, 'recommendations, status:', responseStatus);

        setRecommendations(recs);
        setStatus(responseStatus);
        setRefreshError(null);
        if (isCacheable(responseStatus)) {
          saveToCache({ status: responseStatus, recommendations: recs });
        }
      } catch (err) {
        if (requestId !== requestSeq.current) return;
        console.error('[Azure Rightsizing] Fetch failed:', err);
        if (forceRefresh) {
          // The earlier rows stay, but say the refresh failed instead of looking refreshed.
          setRefreshError(err instanceof Error ? err.message : 'Refresh failed');
        } else {
          setRecommendations([]);
          setStatus('API_ERROR');
        }
      } finally {
        if (requestId === requestSeq.current) {
          setIsLoading(false);
          setIsRefreshing(false);
        }
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
        if (isDifferent) {
          setRecommendations([]);
          setStatus(null);
          setRefreshError(null);
        }
        loadRecommendations(false);
      }
    } else {
      setRecommendations([]);
      setStatus(null);
      setIsLoading(false);
    }
  }, [selectedAccountId, loadRecommendations]);

  return { recommendations, status, isLoading, isRefreshing, refreshError, loadRecommendations };
};
