import { useState, useEffect, useCallback, useRef } from 'react';
import type { AzureAlertsData, AzureAlertsStatus } from '../types/azureAlerts';

interface UseAzureAlertsDataParams {
  selectedAccountId: string | null;
}

interface UseAzureAlertsDataReturn {
  alertsData: AzureAlertsData | null;
  isLoading: boolean;
  isRefreshing: boolean;
  loadAlertsData: (forceRefresh?: boolean) => Promise<void>;
}

const CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

export const useAzureAlertsData = ({
  selectedAccountId,
}: UseAzureAlertsDataParams): UseAzureAlertsDataReturn => {
  const [alertsData, setAlertsData] = useState<AzureAlertsData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

  const loadFromCache = useCallback((): AzureAlertsData | null => {
    if (!selectedAccountId) return null;
    try {
      const cached = localStorage.getItem(`azure_alerts_${selectedAccountId}`);
      const expiry = localStorage.getItem(`azure_alerts_${selectedAccountId}_expiry`);
      if (cached && expiry && Date.now() <= parseInt(expiry)) {
        const data = JSON.parse(cached) as AzureAlertsData;
        if (!data.status) return null; // stale pre-wrapper cache — re-fetch
        console.log('[Azure Alerts] Loaded from cache: status:', data.status,
          'alerts:', data.alerts?.length);
        return data;
      }
    } catch (err) {
      console.error('[Azure Alerts] Cache read failed:', err);
    }
    return null;
  }, [selectedAccountId]);

  const saveToCache = useCallback((data: AzureAlertsData) => {
    if (!selectedAccountId) return;
    try {
      localStorage.setItem(`azure_alerts_${selectedAccountId}`, JSON.stringify(data));
      localStorage.setItem(
        `azure_alerts_${selectedAccountId}_expiry`,
        (Date.now() + CACHE_DURATION).toString()
      );
    } catch (err) {
      console.error('[Azure Alerts] Cache write failed:', err);
    }
  }, [selectedAccountId]);

  const clearCache = useCallback(() => {
    if (!selectedAccountId) return;
    localStorage.removeItem(`azure_alerts_${selectedAccountId}`);
    localStorage.removeItem(`azure_alerts_${selectedAccountId}_expiry`);
  }, [selectedAccountId]);

  const loadAlertsData = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setAlertsData(null);
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
          setAlertsData(cached);
          setIsLoading(false);
          isFetchingRef.current = false;
          return;
        }
        setIsLoading(true);
      }

      try {
        const url = `/api/xamops/azure/alerts?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;
        console.log('[Azure Alerts] Fetching:', url);

        const res = await fetch(url, {
          credentials: 'include',
          headers: { Accept: 'application/json' },
        });

        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const raw = await res.json();
        const data: AzureAlertsData = {
          status: (raw.status as AzureAlertsStatus) ?? 'API_ERROR',
          alerts: Array.isArray(raw.alerts) ? raw.alerts : [],
        };

        console.log('[Azure Alerts] Received: status:', data.status,
          'alerts:', data.alerts.length);

        setAlertsData(data);
        saveToCache(data);
      } catch (err) {
        console.error('[Azure Alerts] Fetch failed:', err);
        if (!forceRefresh) {
          setAlertsData({ status: 'API_ERROR', alerts: [] });
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
        loadAlertsData(false);
      }
    } else {
      setAlertsData(null);
      setIsLoading(false);
    }
  }, [selectedAccountId, loadAlertsData]);

  return { alertsData, isLoading, isRefreshing, loadAlertsData };
};
