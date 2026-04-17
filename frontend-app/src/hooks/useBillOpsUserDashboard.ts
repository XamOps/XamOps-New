/**
 * useBillOpsUserDashboard Hook
 *
 * Converted from: billops/dashboard_user.html (userDashboard Alpine.js component)
 *
 * Manages BillOps user dashboard data fetching with:
 * - LocalStorage caching for instant load
 * - Background polling every 30 seconds
 * - Manual refresh capability
 * - Proper cleanup on unmount
 *
 * What stayed the same:
 * - Same API endpoint: /api/billops/dashboard/user
 * - Same caching strategy (localStorage)
 * - Same polling interval (30 seconds)
 * - Same forceRefresh parameter logic
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { apiFetch } from '../utils/apiClient';
import type { BillOpsUserDashboardData, UseBillOpsUserDashboardReturn } from '../types/billopsUserDashboard';
import { useAccounts } from '../contexts/AccountContext';
import { useAuth } from '../contexts/AuthContext';

const POLL_INTERVAL = 30000; // 30 seconds

export const useBillOpsUserDashboard = (): UseBillOpsUserDashboardReturn => {
  const { accounts, isLoading: accountsLoading } = useAccounts();
  const { userProfile } = useAuth();
  const cacheKeyRef = useRef(`billops_user_dashboard_data:${userProfile?.username ?? 'unknown'}`);
  useEffect(() => {
    cacheKeyRef.current = `billops_user_dashboard_data:${userProfile?.username ?? 'unknown'}`;
  }, [userProfile?.username]);

  const [data, setData] = useState<BillOpsUserDashboardData | null>(null);
  const [loading, setLoading] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const pollingIntervalRef = useRef<number | null>(null);
  const isMountedRef = useRef(true);

  /**
   * Load dashboard data from API
   * Mimics exact behavior from original userDashboard() function
   */
  const loadDashboard = useCallback(async (forceRefresh: boolean = false) => {
    // Show loading spinner ONLY if there's no data yet (first time load)
    if (!data) {
      setLoading(true);
    }
    setError(null);

    try {
      const response = await apiFetch(`/api/billops/dashboard/user?forceRefresh=${forceRefresh}`, {
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const newData: BillOpsUserDashboardData = await response.json();

      // Store in LocalStorage for next time
      localStorage.setItem(cacheKeyRef.current, JSON.stringify(newData));

      // Smoothly update data if changed
      if (isMountedRef.current) {
        const currentDataStr = JSON.stringify(data);
        const newDataStr = JSON.stringify(newData);

        if (currentDataStr !== newDataStr || !data) {
          setData(newData);
          console.log('✅ [BillOps] Dashboard data updated');
        }
      }
    } catch (err) {
      console.error('Error loading user dashboard:', err);
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
   * Initialize: Fetch fresh data and start polling (only when accounts are available)
   */
  useEffect(() => {
    isMountedRef.current = true;

    if (accountsLoading) return;
    if (accounts.length === 0) {
      setLoading(false);
      return () => { isMountedRef.current = false; };
    }

    // Fetch fresh data from backend
    loadDashboard(false);

    // Start background polling every 30 seconds
    pollingIntervalRef.current = window.setInterval(() => {
      console.log('🔄 Auto-refreshing BillOps dashboard...');
      loadDashboard(false);
    }, POLL_INTERVAL);

    // Cleanup on unmount
    return () => {
      isMountedRef.current = false;
      if (pollingIntervalRef.current) {
        clearInterval(pollingIntervalRef.current);
      }
    };
  }, [accounts.length, accountsLoading]); // eslint-disable-line react-hooks/exhaustive-deps

  return {
    data,
    loading,
    isRefreshing,
    error,
    refreshDashboard,
  };
};

export default useBillOpsUserDashboard;
