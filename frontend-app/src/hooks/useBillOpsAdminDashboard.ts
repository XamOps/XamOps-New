/**
 * useBillOpsAdminDashboard Hook
 *
 * Converted from: billops/dashboard_admin.html (adminDashboard Alpine.js component)
 *
 * Manages BillOps admin dashboard data fetching with:
 * - LocalStorage caching for instant load
 * - Background polling every 30 seconds
 * - Manual refresh capability
 * - Proper cleanup on unmount
 *
 * What stayed the same:
 * - Same API endpoint: /api/billops/dashboard/admin
 * - Same caching strategy (localStorage)
 * - Same polling interval (30 seconds)
 * - Same forceRefresh parameter logic
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { apiFetch } from '../utils/apiClient';
import type { BillOpsAdminDashboardData, UseBillOpsAdminDashboardReturn } from '../types/billopsAdminDashboard';

const CACHE_KEY = 'billops_admin_dashboard_data';
const POLL_INTERVAL = 30000; // 30 seconds

export const useBillOpsAdminDashboard = (): UseBillOpsAdminDashboardReturn => {
  const [data, setData] = useState<BillOpsAdminDashboardData | null>(null);
  const [loading, setLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pollingIntervalRef = useRef<number | null>(null);
  const isMountedRef = useRef(true);

  /**
   * Load dashboard data from API
   * Mimics exact behavior from original adminDashboard() function
   */
  const loadDashboard = useCallback(async (forceRefresh: boolean = false) => {
    // Show loading spinner ONLY if there's no data yet (first time load)
    if (!data) {
      setLoading(true);
    }
    setError(null);

    try {
      const response = await apiFetch(`/api/billops/dashboard/admin?forceRefresh=${forceRefresh}`, {
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const newData: BillOpsAdminDashboardData = await response.json();

      // Store in LocalStorage for next time
      localStorage.setItem(CACHE_KEY, JSON.stringify(newData));

      // Smoothly update data if changed
      if (isMountedRef.current) {
        const currentDataStr = JSON.stringify(data);
        const newDataStr = JSON.stringify(newData);

        if (currentDataStr !== newDataStr || !data) {
          setData(newData);
          console.log('✅ [BillOps Admin] Dashboard data updated');
        }
      }
    } catch (err) {
      console.error('Error loading admin dashboard:', err);
      // Don't show error on background refresh, only on manual refresh
      if (forceRefresh && isMountedRef.current) {
        setError('Failed to load dashboard data. Please try again.');
      }
    } finally {
      if (isMountedRef.current) {
        setLoading(false);
        setIsRefreshing(false);
      }
    }
  }, [data]);

  /**
   * Manual refresh function - used by refresh button
   */
  const refreshDashboard = useCallback(() => {
    setIsRefreshing(true);
    loadDashboard(true);
  }, [loadDashboard]);

  /**
   * Initialize: Load from cache, then fetch fresh data
   */
  useEffect(() => {
    isMountedRef.current = true;

    // 1. Load from LocalStorage immediately (Instant Load)
    const cached = localStorage.getItem(CACHE_KEY);
    if (cached) {
      try {
        const cachedData = JSON.parse(cached) as BillOpsAdminDashboardData;
        setData(cachedData);
        console.log('✅ Loaded BillOps admin dashboard data from LocalStorage');
      } catch (e) {
        console.error('Failed to parse cached admin dashboard data', e);
      }
    }

    // 2. Fetch fresh data from backend (Background)
    loadDashboard(false);

    // 3. Start background polling every 30 seconds
    pollingIntervalRef.current = window.setInterval(() => {
      console.log('🔄 Auto-refreshing BillOps admin dashboard...');
      loadDashboard(false);
    }, POLL_INTERVAL);

    // Cleanup on unmount
    return () => {
      isMountedRef.current = false;
      if (pollingIntervalRef.current) {
        clearInterval(pollingIntervalRef.current);
      }
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    data,
    loading,
    isRefreshing,
    error,
    refreshDashboard,
  };
};

export default useBillOpsAdminDashboard;
