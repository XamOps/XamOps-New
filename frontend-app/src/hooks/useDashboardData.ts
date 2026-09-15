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
  /**
   * @param force re-run every upstream scan. Default true, matching the Refresh button.
   *   Pass false after a Cost and Usage Report refresh: those figures are already in the
   *   shared cache, and forcing would re-run the Compute Optimizer and waste scans behind
   *   granular/kpi, which outlast the 45s client timeout.
   */
  refreshDashboard: (force?: boolean) => void;
  updateResourceInventory: (inventory: ResourceInventory) => void;
}

export const useDashboardData = ({
  selectedAccountIds,
  isMultiAccountMode,
  autoRefreshInterval = 60000,
}: UseDashboardDataOptions): UseDashboardDataReturn => {
  const [dashboardData, setDashboardData] = useState<DashboardAccount | null>(null);
  const [loadingState, setLoadingState] = useState<LoadingState>({
    global: selectedAccountIds.length > 0, // start loading immediately if accounts are selected
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
  // Account selection the currently displayed data belongs to. Background
  // refreshes for the same selection must not re-show loading skeletons —
  // doing so unmounts open UI (e.g. the Manage KPI Cards modal) every cycle.
  const loadedSelectionRef = useRef<string | null>(null);

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
        loadedSelectionRef.current = null;
        return;
      }

      const selectionKey = [...selectedAccountIds].sort().join(',');
      // Skeletons only when we have nothing to show for this selection
      // (first load or account switch). Background/manual refreshes keep the
      // current UI on screen and swap data in when it arrives.
      const showSkeletons = loadedSelectionRef.current !== selectionKey;

      try {
        // Set loading states
        setLoadingState(prev => ({
          ...prev,
          global: showSkeletons,
          kpi: showSkeletons,
          inventory: showSkeletons,
          cost: showSkeletons,
          optimization: showSkeletons,
          map: showSkeletons,
        }));
        setError(null);

        let newData: DashboardAccount | null = null;

        if (isMultiAccountMode && selectedAccountIds.length > 1) {
          console.log('📊 Loading multi-account data for:', selectedAccountIds);
          // fetchMultiAccountData already normalizes the aggregated backend
          // response into a DashboardAccount shape.
          newData = await fetchMultiAccountData(selectedAccountIds, forceRefresh);
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
          loadedSelectionRef.current = selectionKey;

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
  const refreshDashboard = useCallback((force = true) => {
    setIsRefreshing(true);
    // Use setTimeout to allow state update to process before calling load
    setTimeout(() => loadDashboardData(force), 0);
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
