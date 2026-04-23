/**
 * useBillOpsAdminDashboard Hook
 *
 * Converted from: billops/dashboard_admin.html (adminDashboard Alpine.js component)
 *
 * Manages BillOps admin dashboard data fetching with:
 * - LocalStorage caching for instant load (no skeleton if data was loaded before)
 * - Background polling every 30 seconds (never shows skeleton after first load)
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
import { useAuth } from '../contexts/AuthContext';
import type { BillOpsAdminDashboardData, UseBillOpsAdminDashboardReturn } from '../types/billopsAdminDashboard';

const POLL_INTERVAL = 30000; // 30 seconds

export const useBillOpsAdminDashboard = (): UseBillOpsAdminDashboardReturn => {
  const { userProfile } = useAuth();
  // Key by username so different users never share cached dashboard data.
  // Stored in a ref so the stable loadDashboard callback always reads the latest value.
  const cacheKeyRef = useRef(`billops_admin_dashboard_data:${userProfile?.username ?? 'unknown'}`);
  useEffect(() => {
    cacheKeyRef.current = `billops_admin_dashboard_data:${userProfile?.username ?? 'unknown'}`;
  }, [userProfile?.username]);
  const [data, setData] = useState<BillOpsAdminDashboardData | null>(null);
  const [loading, setLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pollingIntervalRef = useRef<number | null>(null);
  const isMountedRef = useRef(true);
  // Tracks whether ANY data has been shown to the user (from localStorage OR a successful API call).
  // Using a ref instead of state so loadDashboard does not need it as a dependency,
  // avoiding the stale-closure bug where the polling interval always sees hasLoaded=false.
  const hasLoadedRef = useRef<boolean>(false);

  /**
   * Load dashboard data from API.
   * - Shows skeleton (loading=true) ONLY when hasLoadedRef.current is false
   *   (i.e. there is no data to display yet).
   * - All background/polling fetches run silently while existing data stays visible.
   *
   * Note: loadDashboard has NO state dependencies so its reference is stable — the
   * polling interval will always call the latest version without stale-closure issues.
   */
  const loadDashboard = useCallback(async (forceRefresh: boolean = false) => {
    // Show skeleton only when there is nothing to show yet
    if (!hasLoadedRef.current) {
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

      // Persist to localStorage for next page visit
      localStorage.setItem(cacheKeyRef.current, JSON.stringify(newData));

      if (isMountedRef.current) {
        hasLoadedRef.current = true;
        setData(newData);
        console.log('✅ [BillOps Admin] Dashboard data updated');
      }
    } catch (err) {
      console.error('Error loading admin dashboard:', err);
      // Only surface the error on a manual refresh (not silent background polls)
      if (forceRefresh && isMountedRef.current) {
        setError('Failed to load dashboard data. Please try again.');
      }
    } finally {
      if (isMountedRef.current) {
        setLoading(false);
        setIsRefreshing(false);
      }
    }
  }, []); // stable reference — no state deps, uses refs for data-presence check

  /**
   * Manual refresh — called by the Refresh button.
   */
  const refreshDashboard = useCallback(() => {
    setIsRefreshing(true);
    loadDashboard(true);
  }, [loadDashboard]);

  /**
   * Initialise: load from localStorage immediately, then fetch fresh data in background.
   */
  useEffect(() => {
    isMountedRef.current = true;

    // 1. Load from localStorage immediately (instant display, no skeleton)
    const cached = localStorage.getItem(cacheKeyRef.current);
    if (cached) {
      try {
        const cachedData = JSON.parse(cached) as BillOpsAdminDashboardData;
        setData(cachedData);
        hasLoadedRef.current = true; // Data is visible — never show skeleton again
        console.log('✅ Loaded BillOps admin dashboard data from LocalStorage');
      } catch (e) {
        console.error('Failed to parse cached admin dashboard data', e);
      }
    }

    // 2. Fetch fresh data from backend in background
    //    (setLoading(true) is skipped because hasLoadedRef.current=true if localStorage had data)
    loadDashboard(false);

    // 3. Poll every 30 seconds — only when the tab is visible, never shows skeleton
    pollingIntervalRef.current = window.setInterval(() => {
      if (document.visibilityState === 'visible') {
        console.log('🔄 Auto-refreshing BillOps admin dashboard...');
        loadDashboard(false);
      }
    }, POLL_INTERVAL);

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
