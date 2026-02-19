/**
 * Custom hook for Spot Automation data fetching and management
 *
 * Handles all API calls for spot automation including:
 * - Cost data fetching
 * - History data fetching
 * - ASG enable/disable
 * - CloudFormation deployment
 * - Account syncing
 */

import { useState, useEffect, useCallback } from 'react';
import apiClient from '../utils/apiClient';

export interface ASG {
  name: string;
  region: string;
  isEnabled: boolean;
  instanceCount: number;
  spotCount: number;
  onDemandCount: number;
  instanceTypes: string;
  currentCost: number;
  onDemandCost: number;
  savings: number;
  potentialSavings: number;
  updating?: boolean;
}

export interface SpotCosts {
  currentHourly: number;
  currentMonthlyCost: number;
  onDemandHourly: number;
  hourlyActualSavings: number;
}

export interface SpotSavings {
  actualMonthlySavings: number;
  potentialMonthlySavings: number;
}

export interface HistoryDataPoint {
  timestamp: string;
  total_actual_savings: number;
  total_potential_savings: number;
}

export interface HistorySummary {
  total_savings: number;
  average_hourly_savings: number;
  peak_savings: number;
}

interface UseSpotAutomationDataProps {
  selectedAccountId: string | null;
}

export const useSpotAutomationData = ({ selectedAccountId }: UseSpotAutomationDataProps) => {
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  // Data state
  const [asgs, setAsgs] = useState<ASG[]>([]);
  const [costs, setCosts] = useState<SpotCosts>({
    currentHourly: 0.0,
    currentMonthlyCost: 0.0,
    onDemandHourly: 0.0,
    hourlyActualSavings: 0.0,
  });
  const [savings, setSavings] = useState<SpotSavings>({
    actualMonthlySavings: 0.0,
    potentialMonthlySavings: 0.0,
  });
  const [enabledRegionsCount, setEnabledRegionsCount] = useState(0);
  const [totalInstances, setTotalInstances] = useState(0);

  // History data state
  const [historyData, setHistoryData] = useState<HistoryDataPoint[]>([]);
  const [historySummary, setHistorySummary] = useState<HistorySummary>({
    total_savings: 0,
    average_hourly_savings: 0,
    peak_savings: 0,
  });
  const [isLoadingHistory, setIsLoadingHistory] = useState(false);

  /**
   * Fetch cost data for selected account
   */
  const fetchCostData = useCallback(async (refresh = false) => {
    if (!selectedAccountId) {
      console.log('[SpotAutomation] No account selected');
      setIsLoading(false);
      return;
    }

    if (refresh) {
      setIsRefreshing(true);
    } else {
      setIsLoading(true);
    }
    setError(null);

    try {
      console.log('[SpotAutomation] Fetching cost data for account:', selectedAccountId);

      const data = await apiClient.get(`/api/autospotting/costs/${selectedAccountId}`);

      console.log('[SpotAutomation] Cost data received:', data);

      // Update costs
      if (data.summary) {
        setCosts({
          currentHourly: data.summary.total_current_hourly_cost || 0.0,
          currentMonthlyCost: (data.summary.total_current_hourly_cost || 0) * 730,
          onDemandHourly: data.summary.total_ondemand_hourly_cost || 0.0,
          hourlyActualSavings: data.summary.total_actual_savings || 0.0,
        });

        setSavings({
          actualMonthlySavings: (data.summary.total_actual_savings || 0) * 730,
          potentialMonthlySavings: (data.summary.total_potential_savings || 0) * 730,
        });
      }

      // Update ASGs
      if (data.asgs && data.asgs.length > 0) {
        const asgData: ASG[] = data.asgs.map((asg: any, index: number) => ({
          name: asg.asg_name || `ASG-${index}`,
          region: asg.region || 'unknown',
          isEnabled: asg.autospotting_enabled === true,
          instanceCount: asg.instance_count || 0,
          spotCount: asg.spot_instance_count || 0,
          onDemandCount: asg.ondemand_instance_count || 0,
          instanceTypes: asg.instance_types ? asg.instance_types.join(', ') : 'N/A',
          currentCost: asg.current_hourly_cost || 0,
          onDemandCost: asg.ondemand_hourly_cost || 0,
          savings: asg.actual_hourly_savings || 0,
          potentialSavings: asg.potential_hourly_savings || 0,
          updating: false,
        }));

        setAsgs(asgData);
        setEnabledRegionsCount(new Set(asgData.map((a) => a.region)).size);
        setTotalInstances(asgData.reduce((sum, asg) => sum + asg.instanceCount, 0));

        console.log(`[SpotAutomation] Loaded ${asgData.length} ASGs`);
      } else {
        setAsgs([]);
        setEnabledRegionsCount(0);
        setTotalInstances(0);
      }
    } catch (err) {
      console.error('[SpotAutomation] Error fetching cost data:', err);
      setError(err instanceof Error ? err : new Error('Failed to fetch cost data'));
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  }, [selectedAccountId]);

  /**
   * Fetch history data for selected account
   */
  const fetchHistoryData = useCallback(async (timeRange: 'last24h' | 'last7d' | 'last30d' = 'last7d') => {
    if (!selectedAccountId) {
      return;
    }

    setIsLoadingHistory(true);

    try {
      let start: string, end: string, interval: string;
      const now = new Date();

      if (timeRange === 'last24h') {
        start = new Date(now.getTime() - 24 * 60 * 60 * 1000).toISOString();
        interval = 'hourly';
      } else if (timeRange === 'last7d') {
        start = new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000).toISOString();
        interval = 'hourly';
      } else {
        start = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000).toISOString();
        interval = 'daily';
      }
      end = now.toISOString();

      console.log(`[SpotAutomation] Fetching history data for ${timeRange}...`);

      const data = await apiClient.get(
        `/api/autospotting/costs/history/${selectedAccountId}?start=${start}&end=${end}&interval=${interval}`
      );

      console.log('[SpotAutomation] History data received:', data);

      setHistoryData(data.data_points || []);

      const summary = data.summary || {};
      const maxSavings = (data.data_points || []).reduce(
        (max: number, d: HistoryDataPoint) => Math.max(max, d.total_actual_savings || 0),
        0
      );

      setHistorySummary({
        total_savings: summary.total_savings || 0,
        average_hourly_savings: summary.average_hourly_savings || 0,
        peak_savings: maxSavings,
      });
    } catch (err) {
      console.error('[SpotAutomation] Error fetching history data:', err);
    } finally {
      setIsLoadingHistory(false);
    }
  }, [selectedAccountId]);

  /**
   * Toggle ASG enable/disable
   */
  const toggleASG = useCallback(async (asg: ASG): Promise<boolean> => {
    if (!selectedAccountId) {
      throw new Error('No account selected');
    }

    const newState = !asg.isEnabled;
    const endpoint = newState ? 'enable' : 'disable';

    console.log(`[SpotAutomation] Toggling ${asg.name}: ${newState ? 'ENABLE' : 'DISABLE'}`);

    try {
      await apiClient.post(
        `/api/autospotting/asgs/${selectedAccountId}/${endpoint}?region=${asg.region}&asgName=${encodeURIComponent(asg.name)}`
      );

      console.log('[SpotAutomation] Toggle successful');

      // Update local state
      setAsgs((prevAsgs) =>
        prevAsgs.map((a) =>
          a.name === asg.name && a.region === asg.region ? { ...a, isEnabled: newState } : a
        )
      );

      // Refresh data after 10 seconds
      setTimeout(() => {
        console.log('[SpotAutomation] Refreshing data after toggle...');
        fetchCostData(true);
      }, 10000);

      return true;
    } catch (err) {
      console.error('[SpotAutomation] Toggle error:', err);
      throw err;
    }
  }, [selectedAccountId, fetchCostData]);

  /**
   * Sync (backfill) SUCCESS events for Spot instances currently running in an ASG
   * that are not yet recorded in the database. Non-disruptive — does not change ASG config.
   */
  const syncASG = useCallback(async (asg: ASG): Promise<{ backfilled: number; message: string }> => {
    if (!selectedAccountId) {
      throw new Error('No account selected');
    }
    const data = await apiClient.post(
      `/api/autospotting/asgs/${selectedAccountId}/sync?region=${asg.region}&asgName=${encodeURIComponent(asg.name)}`
    );
    return { backfilled: data.backfilled ?? 0, message: data.message ?? '' };
  }, [selectedAccountId]);

  /**
   * Generate CloudFormation deployment URL
   */
  const generateDeploymentUrl = useCallback(async (): Promise<string> => {
    try {
      console.log('[SpotAutomation] Generating CloudFormation URL...');

      const data = await apiClient.get(
        '/api/cloudformation/deploy-url?regions=&deployRegion=us-east-1'
      );

      console.log('[SpotAutomation] CloudFormation URL generated:', data.url);

      return data.url;
    } catch (err) {
      console.error('[SpotAutomation] Error generating deployment URL:', err);
      throw err;
    }
  }, []);

  // Load data when account changes
  useEffect(() => {
    if (selectedAccountId) {
      fetchCostData(false);
      fetchHistoryData('last7d');
    }
  }, [selectedAccountId, fetchCostData, fetchHistoryData]);

  return {
    // Data
    asgs,
    costs,
    savings,
    enabledRegionsCount,
    totalInstances,
    historyData,
    historySummary,

    // Loading states
    isLoading,
    isRefreshing,
    isLoadingHistory,
    error,

    // Actions
    fetchCostData,
    fetchHistoryData,
    toggleASG,
    syncASG,
    generateDeploymentUrl,
  };
};
