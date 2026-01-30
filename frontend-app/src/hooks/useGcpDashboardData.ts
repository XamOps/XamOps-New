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
  const isLoadingRef = useRef(false);

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
        sessionStorage.setItem(cacheKey, JSON.stringify(dashboardData));
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

      if (isLoadingRef.current) {
        console.log('🔷 GCP Dashboard: Already loading, skipping');
        return;
      }

      isLoadingRef.current = true;
      setLoading((prev) => ({ ...prev, global: true }));
      setError(null);

      try {
        // Try to load from cache first if not force refresh
        if (!forceRefresh) {
          const cachedData = loadFromCache();
          if (cachedData) {
            console.log('🔷 GCP Dashboard: Loaded from cache', cachedData);
            setData(cachedData);
            setLoading({
              global: false,
              kpi: false,
              inventory: false,
              cost: false,
              optimization: false,
              map: false,
            });
            isLoadingRef.current = false;
            return;
          }
        }

        // Fetch from API
        console.log('🔷 GCP Dashboard: Fetching from API');
        let dashboardData: GcpDashboardData | GcpMultiAccountDashboardData;

        if (multiAccountMode && accountIds.length > 1) {
          console.log('🔷 GCP Dashboard: Multi-account mode', accountIds);
          dashboardData = await fetchGcpMultiAccountDashboardData(accountIds);
        } else {
          console.log('🔷 GCP Dashboard: Single account mode', accountIds[0]);
          dashboardData = await fetchGcpDashboardData(accountIds[0], forceRefresh);
        }

        console.log('🔷 GCP Dashboard: Data received', dashboardData);

        // Update state
        setData(dashboardData);

        let updateTimestamp: string | null = null;
        if (dashboardData) {
          if ('lastUpdated' in dashboardData) {
            updateTimestamp = dashboardData.lastUpdated;
          } else if ('aggregated' in dashboardData && dashboardData.aggregated) {
            updateTimestamp = dashboardData.aggregated.lastUpdated;
          }
        }

        setLastUpdated(updateTimestamp || new Date().toISOString());

        // Save to cache
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
        isLoadingRef.current = false;
      }
    },
    [accountIds, multiAccountMode, loadFromCache, saveToCache]
  );

  // Refresh handler
  const refresh = useCallback(async () => {
    await loadDashboardData(false); // Note: forceRefresh is ignored by backend for security
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

      // Update the dashboard data with new inventory
      // Note: The inventory object from WebSocket may have different structure
      // We'll refresh the data from cache or trigger a reload
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

  // Initial load
  useEffect(() => {
    loadDashboardData(false);
  }, [accountIds, multiAccountMode]);

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
