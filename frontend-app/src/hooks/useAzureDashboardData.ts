import { useState, useEffect, useCallback, useRef } from 'react';
import {
  AzureDashboardData,
  AzureDashboardLoadingState,
  AzureDashboardError,
} from '../types/azureDashboard';
import { fetchAzureDashboardData } from '../api/azureDashboardApi';

interface UseAzureDashboardDataProps {
  accountIds: string[];
  autoRefresh?: boolean;
  refreshInterval?: number; // in seconds
}

interface UseAzureDashboardDataReturn {
  data: AzureDashboardData | null;
  loading: AzureDashboardLoadingState;
  error: AzureDashboardError | null;
  refresh: () => Promise<void>;
  clearError: () => void;
  lastUpdated: string | null;
}

const CACHE_KEY_PREFIX = 'azure_dashboard_';
const DEFAULT_REFRESH_INTERVAL = 60; // 60 seconds

/**
 * How long the sessionStorage copy may be used to paint the page.
 *
 * The session cache exists only so a remount (tab switch, route change) shows the last
 * known figures instead of an empty dashboard while the request is in flight. It is NOT a
 * substitute for fetching.
 *
 * It used to have no age limit, and `loadDashboardData(false)` returned it before ever
 * calling the API. Since the auto-refresh timer also calls `loadDashboardData(false)`, the
 * effect was that the Azure dashboard made exactly one request per browser session: the
 * 60-second poll read sessionStorage, found a hit, and returned. A backend refresh was
 * invisible until the user hard-reloaded or pressed Refresh. The AWS dashboard has no
 * session cache at all and genuinely re-fetches every 60s, which is most of why AWS looked
 * live and Azure looked frozen.
 *
 * Kept shorter than the poll interval so a poll can never be served from it.
 */
const SESSION_CACHE_MAX_AGE_MS = 30_000;

interface CachedEnvelope {
  data: AzureDashboardData;
  cachedAt: number;
}

export const useAzureDashboardData = ({
  accountIds,
  autoRefresh = true,
  refreshInterval = DEFAULT_REFRESH_INTERVAL,
}: UseAzureDashboardDataProps): UseAzureDashboardDataReturn => {
  const [data, setData] = useState<AzureDashboardData | null>(null);
  const [loading, setLoading] = useState<AzureDashboardLoadingState>({
    global: false,
    kpi: false,
    inventory: false,
    cost: false,
    optimization: false,
    map: false,
  });
  const [error, setError] = useState<AzureDashboardError | null>(null);
  const [lastUpdated, setLastUpdated] = useState<string | null>(null);

  const refreshTimerRef = useRef<NodeJS.Timeout | null>(null);
  const isFetchingRef = useRef(false);
  const previousAccountIdsRef = useRef<string[]>([]);
  const initializedRef = useRef(false);

  // Get cache key
  const getCacheKey = useCallback(() => {
    return `${CACHE_KEY_PREFIX}${accountIds[0]}`;
  }, [accountIds]);

  // Load from cache — only honoured while within SESSION_CACHE_MAX_AGE_MS.
  const loadFromCache = useCallback((): AzureDashboardData | null => {
    try {
      const cacheKey = getCacheKey();
      const cached = sessionStorage.getItem(cacheKey);
      if (!cached) return null;

      const envelope = JSON.parse(cached) as CachedEnvelope | AzureDashboardData;

      // Entries written before this envelope existed have no cachedAt. Treat them as
      // expired rather than trusting them forever, which is the bug being fixed.
      if (!('cachedAt' in envelope) || typeof envelope.cachedAt !== 'number') {
        sessionStorage.removeItem(cacheKey);
        return null;
      }

      if (Date.now() - envelope.cachedAt > SESSION_CACHE_MAX_AGE_MS) {
        return null;
      }

      setLastUpdated(envelope.data?.lastUpdated || new Date().toISOString());
      return envelope.data;
    } catch (err) {
      console.error('Failed to load from cache:', err);
    }
    return null;
  }, [getCacheKey]);

  // Save to cache
  const saveToCache = useCallback(
    (dashboardData: AzureDashboardData) => {
      try {
        const cacheKey = getCacheKey();
        const envelope: CachedEnvelope = { data: dashboardData, cachedAt: Date.now() };
        sessionStorage.setItem(cacheKey, JSON.stringify(envelope));
      } catch (err) {
        console.error('Failed to save to cache:', err);
      }
    },
    [getCacheKey]
  );

  // Load dashboard data.
  //
  // forceRefresh        -> ask the backend for a live Azure fetch (slow, expensive)
  // bypassSessionCache  -> skip the browser copy but still let the backend serve its
  //                        snapshot. This is what the poll uses: cheap for Azure, but it
  //                        does pick up whatever the scheduled refresh last wrote.
  const loadDashboardData = useCallback(
    async (forceRefresh: boolean = false, bypassSessionCache: boolean = false) => {
      console.log('🔷 Azure Dashboard: loadDashboardData called', {
        accountIds,
        forceRefresh,
        bypassSessionCache,
      });

      if (accountIds.length === 0) {
        console.log('🔷 Azure Dashboard: No account IDs, clearing data');
        setData(null);
        setError(null);
        return;
      }

      if (isFetchingRef.current && !forceRefresh) {
        console.log('🔷 Azure Dashboard: Already loading, skipping');
        return;
      }

      isFetchingRef.current = true;
      setLoading((prev) => ({ ...prev, global: true }));
      setError(null);

      try {
        // Try to load from cache first if not force refresh
        if (!forceRefresh && !bypassSessionCache) {
          const cachedData = loadFromCache();
          if (cachedData) {
            console.log('🔷 Azure Dashboard: Loaded from cache (fresh, < 30s old)');
            setData(cachedData);
            setLoading({
              global: false,
              kpi: false,
              inventory: false,
              cost: false,
              optimization: false,
              map: false,
            });
            isFetchingRef.current = false;
            return;
          }
        }

        // Fetch from API
        console.log('🔷 Azure Dashboard: Fetching from API');
        const dashboardData = await fetchAzureDashboardData(accountIds[0], forceRefresh);

        setData(dashboardData);
        setLastUpdated(dashboardData.lastUpdated || new Date().toISOString());
        saveToCache(dashboardData);

        setLoading({
          global: false,
          kpi: false,
          inventory: false,
          cost: false,
          optimization: false,
          map: false,
        });
      } catch (err: any) {
        console.error('🔷 Azure Dashboard: Error loading data', err);
        setError({
          section: 'global',
          message: err.message || 'Failed to load Azure dashboard data',
          timestamp: new Date().toISOString(),
        });
        setLoading({
          global: false,
          kpi: false,
          inventory: false,
          cost: false,
          optimization: false,
          map: false,
        });
      } finally {
        isFetchingRef.current = false;
      }
    },
    [accountIds, loadFromCache, saveToCache]
  );

  // Refresh function
  const refresh = useCallback(async () => {
    console.log('🔷 Azure Dashboard: Manual refresh triggered');
    await loadDashboardData(true);
  }, [loadDashboardData]);

  // Clear error
  const clearError = useCallback(() => {
    setError(null);
  }, []);

  // Initial load and account change detection
  useEffect(() => {
    const accountIdsChanged = JSON.stringify(previousAccountIdsRef.current) !== JSON.stringify(accountIds);

    if (accountIdsChanged || !initializedRef.current) {
      console.log('🔷 Azure Dashboard: Account IDs changed or initial load', { accountIds, accountIdsChanged });
      previousAccountIdsRef.current = accountIds;
      initializedRef.current = true;

      // Clear existing data when accounts change
      if (accountIdsChanged && accountIds.length > 0) {
        setData(null);
      }

      // Load dashboard data
      loadDashboardData(false);
    }
  }, [accountIds, loadDashboardData]);

  // Auto-refresh
  useEffect(() => {
    if (!autoRefresh || accountIds.length === 0) {
      return;
    }

    console.log(`🔷 Azure Dashboard: Setting up auto-refresh (${refreshInterval}s)`);

    // Clear existing timer
    if (refreshTimerRef.current) {
      clearInterval(refreshTimerRef.current);
    }

    // Set up new timer.
    //
    // bypassSessionCache=true is the whole point of the timer. With it false the poll was
    // served from sessionStorage and never reached the network, so an open tab could not
    // see a backend refresh for the life of the session.
    refreshTimerRef.current = setInterval(() => {
      console.log('🔷 Azure Dashboard: Auto-refresh triggered (server read)');
      loadDashboardData(false, true);
    }, refreshInterval * 1000);

    // Cleanup
    return () => {
      if (refreshTimerRef.current) {
        clearInterval(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
    };
  }, [autoRefresh, refreshInterval, accountIds, loadDashboardData]);

  return {
    data,
    loading,
    error,
    refresh,
    clearError,
    lastUpdated,
  };
};
