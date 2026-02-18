/**
 * useAksDashboard Hook
 *
 * Custom hook for AKS cluster dashboard data fetching and state management.
 * Mirrors useGkeDashboard.ts but uses resourceGroup instead of location.
 */

import { useState, useCallback, useEffect, useMemo } from 'react';
import apiClient from '../utils/apiClient';
import { AksDashboard } from '../types/aksTypes';
import { GkeTabType } from '../types/gkeTypes';

interface UseAksDashboardParams {
  clusterName: string | null;
  resourceGroup: string;
  accountId: string | null;
}

interface UseAksDashboardReturn {
  dashboard: AksDashboard | null;
  loading: boolean;
  isRefreshing: boolean;
  error: string | null;
  activeTab: GkeTabType;
  setActiveTab: (tab: GkeTabType) => void;
  refresh: () => Promise<void>;
  totalPodsCount: number;
  getPodsCountByPhase: (phase: string) => number;
  getTimeSince: (timestamp: number) => string;
  allContainersWithMetrics: any[];
  totalContainersCount: number;
  getContainersCountByState: (state: string) => number;
  sortedNodesByPressure: any[];
  getNodeConditionCount: (condition: string) => number;
}

export const useAksDashboard = ({
  clusterName,
  resourceGroup,
  accountId
}: UseAksDashboardParams): UseAksDashboardReturn => {

  const [dashboard, setDashboard] = useState<AksDashboard | null>(null);
  const [loading, setLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<GkeTabType>('overview');

  const fetchDashboard = useCallback(async (forceRefresh = false) => {
    if (!clusterName || !accountId) {
      setLoading(false);
      return;
    }

    if (forceRefresh) {
      setIsRefreshing(true);
    } else {
      setLoading(true);
    }
    setError(null);

    try {
      const data = await apiClient.get<AksDashboard>(
        `/api/xamops/aks/${encodeURIComponent(clusterName)}/dashboard?accountId=${accountId}&resourceGroup=${encodeURIComponent(resourceGroup)}&forceRefresh=${forceRefresh}`
      );
      setDashboard(data);
    } catch (err: any) {
      console.error('Failed to fetch AKS dashboard', err);
      setError(err.message || 'Failed to load cluster dashboard');
    } finally {
      setLoading(false);
      setIsRefreshing(false);
    }
  }, [clusterName, resourceGroup, accountId]);

  useEffect(() => {
    fetchDashboard(false);
  }, [fetchDashboard]);

  const refresh = useCallback(() => fetchDashboard(true), [fetchDashboard]);

  const totalPodsCount = dashboard?.workloadHealth?.pods?.length ?? 0;

  const getPodsCountByPhase = useCallback((phase: string): number => {
    if (!dashboard?.workloadHealth?.pods) return 0;
    return dashboard.workloadHealth.pods.filter(p =>
      p.phase?.toLowerCase() === phase.toLowerCase()
    ).length;
  }, [dashboard]);

  const getTimeSince = useCallback((timestamp: number): string => {
    const diff = Date.now() - timestamp;
    const minutes = Math.floor(diff / 60000);
    if (minutes < 1) return 'just now';
    if (minutes < 60) return `${minutes}m ago`;
    const hours = Math.floor(minutes / 60);
    if (hours < 24) return `${hours}h ago`;
    return `${Math.floor(hours / 24)}d ago`;
  }, []);

  const allContainersWithMetrics = useMemo(
    () => dashboard?.containerMetrics?.containers ?? [],
    [dashboard]
  );
  const totalContainersCount = allContainersWithMetrics.length;

  const getContainersCountByState = useCallback((state: string): number =>
    allContainersWithMetrics.filter((c: any) => c.state === state).length,
  [allContainersWithMetrics]);

  const sortedNodesByPressure = useMemo(
    () => [...(dashboard?.nodeMetrics?.nodes ?? [])].sort((a: any, b: any) =>
      ((b.diskPressure || b.memoryPressure) ? 1 : 0) - ((a.diskPressure || a.memoryPressure) ? 1 : 0)
    ),
    [dashboard]
  );

  const getNodeConditionCount = useCallback((condition: string): number =>
    (dashboard?.nodeMetrics?.nodes ?? []).filter((n: any) => n[condition]).length,
  [dashboard]);

  return {
    dashboard,
    loading,
    isRefreshing,
    error,
    activeTab,
    setActiveTab,
    refresh,
    totalPodsCount,
    getPodsCountByPhase,
    getTimeSince,
    allContainersWithMetrics,
    totalContainersCount,
    getContainersCountByState,
    sortedNodesByPressure,
    getNodeConditionCount,
  };
};
