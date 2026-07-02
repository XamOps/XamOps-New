import { useState, useEffect, useCallback, useRef } from 'react';
import type { AzureSecurityData, AzureSecurityStatus } from '../types/azureSecurity';

interface UseAzureSecurityDataParams {
  selectedAccountId: string | null;
}

interface UseAzureSecurityDataReturn {
  securityData: AzureSecurityData | null;
  isLoading: boolean;
  isRefreshing: boolean;
  loadSecurityData: (forceRefresh?: boolean) => Promise<void>;
}

const CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

export const useAzureSecurityData = ({
  selectedAccountId,
}: UseAzureSecurityDataParams): UseAzureSecurityDataReturn => {
  const [securityData, setSecurityData] = useState<AzureSecurityData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

  const loadFromCache = useCallback((): AzureSecurityData | null => {
    if (!selectedAccountId) return null;
    try {
      const cached = localStorage.getItem(`azure_security_${selectedAccountId}`);
      const expiry = localStorage.getItem(`azure_security_${selectedAccountId}_expiry`);
      if (cached && expiry && Date.now() <= parseInt(expiry)) {
        const data = JSON.parse(cached) as AzureSecurityData;
        if (!data.status) return null; // stale pre-wrapper cache — re-fetch
        console.log('[Azure Security] Loaded from cache: status:', data.status,
          'alerts:', data.alerts?.length, 'assessments:', data.assessments?.length);
        return data;
      }
    } catch (err) {
      console.error('[Azure Security] Cache read failed:', err);
    }
    return null;
  }, [selectedAccountId]);

  const saveToCache = useCallback((data: AzureSecurityData) => {
    if (!selectedAccountId) return;
    try {
      localStorage.setItem(`azure_security_${selectedAccountId}`, JSON.stringify(data));
      localStorage.setItem(
        `azure_security_${selectedAccountId}_expiry`,
        (Date.now() + CACHE_DURATION).toString()
      );
    } catch (err) {
      console.error('[Azure Security] Cache write failed:', err);
    }
  }, [selectedAccountId]);

  const clearCache = useCallback(() => {
    if (!selectedAccountId) return;
    localStorage.removeItem(`azure_security_${selectedAccountId}`);
    localStorage.removeItem(`azure_security_${selectedAccountId}_expiry`);
  }, [selectedAccountId]);

  const loadSecurityData = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setSecurityData(null);
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
          setSecurityData(cached);
          setIsLoading(false);
          isFetchingRef.current = false;
          return;
        }
        setIsLoading(true);
      }

      try {
        const url = `/api/xamops/azure/security?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;
        console.log('[Azure Security] Fetching:', url);

        const res = await fetch(url, {
          credentials: 'include',
          headers: { Accept: 'application/json' },
        });

        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const raw = await res.json();
        const data: AzureSecurityData = {
          status: (raw.status as AzureSecurityStatus) ?? 'API_ERROR',
          secureScore: raw.secureScore ?? 0,
          maxScore: raw.maxScore ?? 100,
          alerts: Array.isArray(raw.alerts) ? raw.alerts : [],
          assessments: Array.isArray(raw.assessments) ? raw.assessments : [],
          scoreControls: Array.isArray(raw.scoreControls) ? raw.scoreControls : [],
        };

        console.log('[Azure Security] Received: status:', data.status,
          'score:', data.secureScore, 'alerts:', data.alerts.length,
          'assessments:', data.assessments.length, 'controls:', data.scoreControls.length);

        setSecurityData(data);
        saveToCache(data);
      } catch (err) {
        console.error('[Azure Security] Fetch failed:', err);
        if (!forceRefresh) {
          setSecurityData({
            status: 'API_ERROR',
            secureScore: 0,
            maxScore: 100,
            alerts: [],
            assessments: [],
            scoreControls: [],
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
        loadSecurityData(false);
      }
    } else {
      setSecurityData(null);
      setIsLoading(false);
    }
  }, [selectedAccountId, loadSecurityData]);

  return { securityData, isLoading, isRefreshing, loadSecurityData };
};
