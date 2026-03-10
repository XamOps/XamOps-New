/**
 * useDashboardData Hook
 *
 * Converted from: dashboard.html (dashboardApp data fetching logic)
 *
 * Manages dashboard data fetching for single and multi-account modes.
 * Preserves exact same API calling patterns as original.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import {
  fetchMultiAccountData,
  fetchAllGranularData,
} from '../services/dashboardApi';
import type {
  DashboardAccount,
  LoadingState,
  DashboardError,
  ResourceInventory,
} from '../types/liveDashboard';

interface UseDashboardDataOptions {
  selectedAccountIds: string[];
  isMultiAccountMode: boolean;
  autoRefreshInterval?: number; // milliseconds, default 60000 (60 seconds)
}

interface UseDashboardDataReturn {
  dashboardData: DashboardAccount | null;
  loadingState: LoadingState;
  error: DashboardError | null;
  isRefreshing: boolean;
  loadDashboardData: (forceRefresh?: boolean) => Promise<void>;
  refreshDashboard: () => void;
  updateResourceInventory: (inventory: ResourceInventory) => void;
}

export const useDashboardData = ({
  selectedAccountIds,
  isMultiAccountMode,
  autoRefreshInterval = 60000,
}: UseDashboardDataOptions): UseDashboardDataReturn => {
  const [dashboardData, setDashboardData] = useState<DashboardAccount | null>(null);
  const [loadingState, setLoadingState] = useState<LoadingState>({
    global: false,
    kpi: false,
    inventory: false,
    cost: false,
    optimization: false,
    map: false,
  });
  const [error, setError] = useState<DashboardError | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const pollIntervalRef = useRef<number | null>(null);
  const isMountedRef = useRef(true);
  const prevSelectedIdsRef = useRef<string[]>([]);

  // Debug: Log when hook inputs change
  useEffect(() => {
    if (JSON.stringify(prevSelectedIdsRef.current) !== JSON.stringify(selectedAccountIds)) {
      console.log('🔄 [useDashboardData] selectedAccountIds changed:', {
        old: prevSelectedIdsRef.current,
        new: selectedAccountIds
      });
      prevSelectedIdsRef.current = selectedAccountIds;
    }
  }, [selectedAccountIds]);

  /**
   * Load dashboard data based on account selection
   */
  const loadDashboardData = useCallback(
    async (forceRefresh: boolean = false) => {
      console.log('🏁 [useDashboardData] loadDashboardData called', {
        selectedAccountIds,
        isMultiAccountMode,
        forceRefresh
      });

      if (selectedAccountIds.length === 0) {
        console.log('⚠️ [useDashboardData] No accounts selected, clearing data');
        setDashboardData(null);
        return;
      }

      try {
        // Set loading states
        setLoadingState(prev => ({
          ...prev,
          global: !isRefreshing && !forceRefresh, // Only set global loading on initial load
          kpi: true,
          inventory: true,
          cost: true,
          optimization: true,
          map: true,
        }));
        setError(null);

        let newData: DashboardAccount | null = null;

        if (isMultiAccountMode && selectedAccountIds.length > 1) {
          console.log('📊 Loading multi-account data for:', selectedAccountIds);
          const data = await fetchMultiAccountData(selectedAccountIds, forceRefresh);
          newData = data.aggregated || data.accounts[0];
        } else {
          const accountId = selectedAccountIds[0];
          console.log('📊 Loading single-account data for:', accountId);
          newData = await fetchAllGranularData(accountId, forceRefresh);
        }

        // Check mount status before updating state
        if (isMountedRef.current && newData) {
          console.log('✅ [useDashboardData] Setting dashboard data:', {
            accountId: newData.accountId,
            hasKpi: !!newData.kpi,
            hasInventory: !!newData.resourceInventory
          });

          setDashboardData(newData);

          setLoadingState({
            global: false,
            kpi: false,
            inventory: false,
            cost: false,
            optimization: false,
            map: false,
          });
        } else {
          console.warn('⚠️ [useDashboardData] Component unmounted or data null, skipping state update');
        }

      } catch (err) {
        console.error('❌ Failed to load dashboard data:', err);
        if (isMountedRef.current) {
          setError({
            message: err instanceof Error ? err.message : 'Failed to load dashboard data',
            timestamp: new Date().toISOString(),
          });
          setLoadingState({
            global: false,
            kpi: false,
            inventory: false,
            cost: false,
            optimization: false,
            map: false,
          });
        }
      } finally {
        if (isMountedRef.current) {
          setIsRefreshing(false);
        }
      }
    },
    [selectedAccountIds, isMultiAccountMode]
  );

  /**
   * Manual refresh function
   */
  const refreshDashboard = useCallback(() => {
    setIsRefreshing(true);
    // Use setTimeout to allow state update to process before calling load
    setTimeout(() => loadDashboardData(true), 0);
  }, [loadDashboardData]);

  /**
   * Setup auto-refresh polling
   */
  useEffect(() => {
    if (selectedAccountIds.length === 0) return;

    // Initial load
    loadDashboardData(false);

    // Setup auto-refresh
    if (autoRefreshInterval > 0) {
      pollIntervalRef.current = window.setInterval(() => {
        console.log('🔄 Auto-refreshing dashboard...');
        loadDashboardData(false);
      }, autoRefreshInterval);
    }

    return () => {
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
      }
    };
  }, [selectedAccountIds, autoRefreshInterval, loadDashboardData]);

  /**
   * Cleanup on unmount
   */
  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      console.log('🛑 [useDashboardData] Unmounting hook');
      isMountedRef.current = false;
      if (pollIntervalRef.current) {
        clearInterval(pollIntervalRef.current);
      }
    };
  }, []);

  /**
   * Update resource inventory from WebSocket
   */
  const updateResourceInventory = useCallback((inventory: ResourceInventory) => {
    setDashboardData(prev => {
      if (!prev) return null;
      return {
        ...prev,
        resourceInventory: inventory,
        lastUpdated: new Date().toISOString()
      };
    });
  }, []);

  return {
    dashboardData,
    loadingState,
    error,
    isRefreshing,
    loadDashboardData,
    refreshDashboard,
    updateResourceInventory,
  };
};
