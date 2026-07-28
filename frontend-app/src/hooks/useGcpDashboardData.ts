import { useState, useEffect, useCallback, useRef } from 'react';
import {
  GcpDashboardData,
  GcpMultiAccountDashboardData,
  GcpDashboardLoadingState,
  GcpDashboardError,
} from '../types/gcpDashboard';
import {
  fetchGcpDashboardData,
  fetchGcpMultiAccountDashboardData,
} from '../api/gcpDashboardApi';
import { useWebSocket } from './useWebSocket';
import { ResourceInventory } from '../types/liveDashboard';

interface UseGcpDashboardDataProps {
  accountIds: string[];
  multiAccountMode: boolean;
  autoRefresh?: boolean;
  refreshInterval?: number; // in seconds
}

interface UseGcpDashboardDataReturn {
  data: GcpDashboardData | GcpMultiAccountDashboardData | null;
  loading: GcpDashboardLoadingState;
  error: GcpDashboardError | null;
  refresh: () => Promise<void>;
  clearError: () => void;
  lastUpdated: string | null;
}

const CACHE_KEY_PREFIX = 'gcp_dashboard_';
const DEFAULT_REFRESH_INTERVAL = 60; // 60 seconds
// Without a TTL this cache never expires for the life of the tab, so a dashboard opened
// before cost data landed keeps showing the stale figures no matter what the server holds.
const CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

export const useGcpDashboardData = ({
  accountIds,
  multiAccountMode,
  autoRefresh = true,
  refreshInterval = DEFAULT_REFRESH_INTERVAL,
}: UseGcpDashboardDataProps): UseGcpDashboardDataReturn => {
  const [data, setData] = useState<GcpDashboardData | GcpMultiAccountDashboardData | null>(
    null
  );
  const [loading, setLoading] = useState<GcpDashboardLoadingState>({
    global: false,
    kpi: false,
    inventory: false,
    cost: false,
    optimization: false,
    map: false,
  });
  const [error, setError] = useState<GcpDashboardError | null>(null);
  const [lastUpdated, setLastUpdated] = useState<string | null>(null);

  const refreshTimerRef = useRef<NodeJS.Timeout | null>(null);
  // Refs for stable execution
  const isFetchingRef = useRef(false);
  const previousAccountIdsRef = useRef<string[]>([]);
  const initializedRef = useRef(false);

  // Get cache key
  const getCacheKey = useCallback(() => {
    if (multiAccountMode) {
      return `${CACHE_KEY_PREFIX}multi_${accountIds.sort().join('_')}`;
    }
    return `${CACHE_KEY_PREFIX}${accountIds[0]}`;
  }, [accountIds, multiAccountMode]);

  // Load from cache
  const loadFromCache = useCallback((): GcpDashboardData | GcpMultiAccountDashboardData | null => {
    try {
      const cacheKey = getCacheKey();
      const cached = sessionStorage.getItem(cacheKey);
      if (cached) {
        const parsedData = JSON.parse(cached);
        const cachedAt = Date.parse(parsedData.cachedAt ?? '');
        if (!parsedData.cachedAt || Number.isNaN(cachedAt) || Date.now() - cachedAt > CACHE_TTL_MS) {
          sessionStorage.removeItem(cacheKey);
          return null;
        }
        setLastUpdated(parsedData.lastUpdated || new Date().toISOString());
        return parsedData;
      }
    } catch (err) {
      console.error('Failed to load from cache:', err);
    }
    return null;
  }, [getCacheKey]);

  // Save to cache
  const saveToCache = useCallback(
    (dashboardData: GcpDashboardData | GcpMultiAccountDashboardData) => {
      try {
        const cacheKey = getCacheKey();
        sessionStorage.setItem(cacheKey, JSON.stringify({
          ...dashboardData,
          cachedAt: new Date().toISOString(),
        }));
      } catch (err) {
        console.error('Failed to save to cache:', err);
      }
    },
    [getCacheKey]
  );

  // Load dashboard data
  const loadDashboardData = useCallback(
    async (forceRefresh: boolean = false) => {
      console.log('🔷 GCP Dashboard: loadDashboardData called', { accountIds, multiAccountMode, forceRefresh });

      if (accountIds.length === 0) {
        console.log('🔷 GCP Dashboard: No account IDs, clearing data');
        setData(null);
        setError(null);
        return;
      }

      if (isFetchingRef.current && !forceRefresh) {
        console.log('🔷 GCP Dashboard: Already loading, skipping');
        return;
      }

      isFetchingRef.current = true;
      setLoading((prev) => ({ ...prev, global: true }));
      setError(null);

      try {
        // Try to load from cache first if not force refresh
        if (!forceRefresh) {
          const cachedData = loadFromCache();
          if (cachedData) {
            console.log('🔷 GCP Dashboard: Loaded from cache');
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
        console.log('🔷 GCP Dashboard: Fetching from API');
        let dashboardData: GcpDashboardData | GcpMultiAccountDashboardData;

        if (multiAccountMode && accountIds.length > 1) {
          dashboardData = await fetchGcpMultiAccountDashboardData(accountIds);
        } else {
          dashboardData = await fetchGcpDashboardData(accountIds[0], forceRefresh);
        }

        setData(dashboardData);

        let updateTimestamp: string | null = null;
        if (dashboardData) {
          if ('lastUpdated' in dashboardData) {
            updateTimestamp = (dashboardData as any).lastUpdated;
          } else if ('aggregated' in dashboardData && dashboardData.aggregated) {
            updateTimestamp = dashboardData.aggregated.lastUpdated;
          }
        }

        setLastUpdated(updateTimestamp || new Date().toISOString());
        saveToCache(dashboardData);
      } catch (err: any) {
        console.error('❌ GCP Dashboard: Failed to load data:', err);
        setError({
          section: 'global',
          message: err.message || 'Failed to load dashboard data',
          timestamp: new Date().toISOString(),
        });
      } finally {
        setLoading({
          global: false,
          kpi: false,
          inventory: false,
          cost: false,
          optimization: false,
          map: false,
        });
        isFetchingRef.current = false;
      }
    },
    [accountIds, multiAccountMode, loadFromCache, saveToCache]
  );

  // Refresh handler
  const refresh = useCallback(async () => {
    await loadDashboardData(true);
  }, [loadDashboardData]);

  // Clear error
  const clearError = useCallback(() => {
    setError(null);
  }, []);

  // WebSocket message handler for inventory updates
  const handleInventoryUpdate = useCallback(
    (inventory: ResourceInventory) => {
      if (!data) return;

      console.log('📦 GCP Dashboard: Real-time inventory update received:', inventory);
      setLastUpdated(new Date().toISOString());
    },
    [data]
  );

  // Setup WebSocket connection for single account mode
  useWebSocket({
    accountId: accountIds.length === 1 && !multiAccountMode ? accountIds[0] : null,
    enabled: accountIds.length === 1 && !multiAccountMode,
    onInventoryUpdate: handleInventoryUpdate,
  });

  // Auto-refresh setup
  useEffect(() => {
    if (refreshTimerRef.current) {
      clearInterval(refreshTimerRef.current);
    }

    if (autoRefresh && accountIds.length > 0) {
      refreshTimerRef.current = setInterval(() => {
        loadDashboardData(false);
      }, refreshInterval * 1000);
    }

    return () => {
      if (refreshTimerRef.current) {
        clearInterval(refreshTimerRef.current);
      }
    };
  }, [autoRefresh, refreshInterval, accountIds, loadDashboardData]);

  // Initial load & account change guard
  useEffect(() => {
    if (accountIds.length > 0) {
      const idsChanged = JSON.stringify(previousAccountIdsRef.current) !== JSON.stringify(accountIds);

      if (idsChanged || !initializedRef.current) {
        console.log('🔷 GCP Dashboard: Account changed or Initial load, fetching data...');
        previousAccountIdsRef.current = [...accountIds];
        initializedRef.current = true;
        loadDashboardData(false);
      }
    } else {
      setData(null);
      setLoading(prev => ({ ...prev, global: false }));
    }
  }, [accountIds, multiAccountMode, loadDashboardData]);

  return {
    data,
    loading,
    error,
    refresh,
    clearError,
    lastUpdated,
  };
};

export default useGcpDashboardData;
