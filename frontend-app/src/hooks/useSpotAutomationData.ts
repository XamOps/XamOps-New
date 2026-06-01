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

import { useState, useEffect, useCallback, useRef } from 'react';
import apiClient from '../utils/apiClient';
import { useAutoSpottingCache } from '../contexts/AutoSpottingCacheContext';

export interface ASG {
  name: string;
  region: string;
  isEnabled: boolean;
  eksManaged: boolean;
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

export interface SecurityGroupInfo {
  groupId: string;
  groupName: string;
}

export interface InstanceDetail {
  instanceId: string;
  instanceType: string;
  availabilityZone: string;
  lifecycle: string;
  state: string;
  asgName: string;
  region: string;
  estimatedHourlySavings: number;
  launchTime: string;
  vpcId: string;
  subnetId: string;
  imageId: string;
  privateIpAddress: string;
  publicIpAddress: string;
  securityGroups: SecurityGroupInfo[];
}

interface UseSpotAutomationDataProps {
  selectedAccountId: string | null;
}

export const useSpotAutomationData = ({ selectedAccountId }: UseSpotAutomationDataProps) => {
  const { getSpotAutomationData, setSpotAutomationData } = useAutoSpottingCache();
  const isInitialLoad = useRef(true);
  const hasHistoryData = useRef(false);
  // Tracks ASGs whose isEnabled state was recently toggled by the user.
  // Key: "{name}|{region}", Value: the toggled boolean. Prevents background
  // refreshes from overwriting the user's action with stale server data.
  const recentlyToggledRef = useRef<Map<string, boolean>>(new Map());

  // Use refs to avoid dependency loops with cache functions
  const getCacheRef = useRef(getSpotAutomationData);
  getCacheRef.current = getSpotAutomationData;
  const setCacheRef = useRef(setSpotAutomationData);
  setCacheRef.current = setSpotAutomationData;

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

    // Check cache on initial load
    if (!refresh && isInitialLoad.current) {
      const cachedData = getCacheRef.current(selectedAccountId);
      if (cachedData) {
        console.log('[SpotAutomation] Using cached data');
        setAsgs(cachedData.asgs);
        setCosts(cachedData.costs);
        setSavings(cachedData.savings);
        setEnabledRegionsCount(cachedData.enabledRegionsCount);
        setTotalInstances(cachedData.totalInstances);
        setHistoryData(cachedData.historyData);
        setHistorySummary(cachedData.historySummary);
        if (cachedData.historyData && cachedData.historyData.length > 0) {
          hasHistoryData.current = true;
        }
        setIsLoading(false);
        isInitialLoad.current = false;
        // Continue to fetch fresh data in background
      }
    }

    if (refresh) {
      setIsRefreshing(true);
    } else if (isInitialLoad.current) {
      setIsLoading(true);
    }
    setError(null);

    try {
      console.log('[SpotAutomation] Fetching cost data for account:', selectedAccountId);

      const data = await apiClient.get(`/api/autospotting/costs/${selectedAccountId}${refresh ? '?forceRefresh=true' : ''}`);

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
        const asgData: ASG[] = data.asgs.map((asg: any, index: number) => {
          const name = asg.asg_name || `ASG-${index}`;
          const region = asg.region || 'unknown';
          const toggleKey = `${name}|${region}`;
          // If user recently toggled this ASG, preserve their choice over server data
          const isEnabled = recentlyToggledRef.current.has(toggleKey)
            ? recentlyToggledRef.current.get(toggleKey)!
            : asg.autospotting_enabled === true;
          return ({
          name,
          region,
          isEnabled,
          eksManaged: asg.eksManaged ?? asg.eks_managed ?? (
            typeof (asg.asg_name || '') === 'string' && (asg.asg_name || '').toLowerCase().startsWith('eks-')
          ),
          instanceCount: asg.instance_count || 0,
          spotCount: asg.spot_instance_count || 0,
          onDemandCount: asg.ondemand_instance_count || 0,
          instanceTypes: asg.instance_types ? asg.instance_types.join(', ') : 'N/A',
          currentCost: asg.current_hourly_cost || 0,
          onDemandCost: asg.ondemand_hourly_cost || 0,
          savings: asg.actual_hourly_savings || 0,
          potentialSavings: asg.potential_hourly_savings || 0,
          updating: false,
        });
        });

        setAsgs(asgData);
        setEnabledRegionsCount(new Set(asgData.map((a) => a.region)).size);
        setTotalInstances(asgData.reduce((sum, asg) => sum + asg.instanceCount, 0));

        console.log(`[SpotAutomation] Loaded ${asgData.length} ASGs`);
        // Update cache with cost data, preserving any existing history data
        const existingCache = getCacheRef.current(selectedAccountId);
        setCacheRef.current(selectedAccountId, {
          asgs: asgData,
          costs: {
            currentHourly: data.summary?.total_current_hourly_cost || 0.0,
            currentMonthlyCost: (data.summary?.total_current_hourly_cost || 0) * 730,
            onDemandHourly: data.summary?.total_ondemand_hourly_cost || 0.0,
            hourlyActualSavings: data.summary?.total_actual_savings || 0.0,
          },
          savings: {
            actualMonthlySavings: (data.summary?.total_actual_savings || 0) * 730,
            potentialMonthlySavings: (data.summary?.total_potential_savings || 0) * 730,
          },
          enabledRegionsCount: new Set(asgData.map((a: ASG) => a.region)).size,
          totalInstances: asgData.reduce((sum: number, asg: ASG) => sum + asg.instanceCount, 0),
          historyData: existingCache?.historyData || [],
          historySummary: existingCache?.historySummary || { total_savings: 0, average_hourly_savings: 0, peak_savings: 0 },
        });
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
      isInitialLoad.current = false;
    }
  }, [selectedAccountId]);

  /**
   * Fetch history data for selected account
   */
  const fetchHistoryData = useCallback(async (timeRange: 'last24h' | 'last7d' | 'last30d' = 'last7d') => {
    if (!selectedAccountId) {
      return;
    }

    // Only show loading spinner if we don't already have data
    if (!hasHistoryData.current) {
      setIsLoadingHistory(true);
    }

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
      if (data.data_points && data.data_points.length > 0) {
        hasHistoryData.current = true;
      }

      const summary = data.summary || {};
      const maxSavings = (data.data_points || []).reduce(
        (max: number, d: HistoryDataPoint) => Math.max(max, d.total_actual_savings || 0),
        0
      );

      const newSummary = {
        total_savings: summary.total_savings || 0,
        average_hourly_savings: summary.average_hourly_savings || 0,
        peak_savings: maxSavings,
      };
      setHistorySummary(newSummary);

      // Update cache with history data
      if (selectedAccountId) {
        const existing = getCacheRef.current(selectedAccountId);
        if (existing) {
          setCacheRef.current(selectedAccountId, {
            ...existing,
            historyData: data.data_points || [],
            historySummary: newSummary,
          });
        } else {
          // Create a partial cache entry so history data isn't lost
          setCacheRef.current(selectedAccountId, {
            asgs: [],
            costs: { currentHourly: 0, currentMonthlyCost: 0, onDemandHourly: 0, hourlyActualSavings: 0 },
            savings: { actualMonthlySavings: 0, potentialMonthlySavings: 0 },
            enabledRegionsCount: 0,
            totalInstances: 0,
            historyData: data.data_points || [],
            historySummary: newSummary,
          });
        }
      }
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

    const toggleKey = `${asg.name}|${asg.region}`;

    try {
      await apiClient.post(
        `/api/autospotting/asgs/${selectedAccountId}/${endpoint}?region=${asg.region}&asgName=${encodeURIComponent(asg.name)}`
      );

      console.log('[SpotAutomation] Toggle successful');

      // Record toggle so background refreshes don't overwrite this user action
      recentlyToggledRef.current.set(toggleKey, newState);

      // Update local state
      setAsgs((prevAsgs) => {
        const updated = prevAsgs.map((a) =>
          a.name === asg.name && a.region === asg.region ? { ...a, isEnabled: newState } : a
        );
        // Also update in-memory cache so page reload reflects the new toggle state
        const existing = getCacheRef.current(selectedAccountId);
        if (existing) {
          setCacheRef.current(selectedAccountId, { ...existing, asgs: updated });
        }
        return updated;
      });

      // After 30 seconds, clear the toggle lock and do one final refresh so the UI
      // is in sync with whatever the backend confirmed (AWS propagation complete by then)
      setTimeout(() => {
        console.log('[SpotAutomation] Clearing toggle lock and refreshing...');
        recentlyToggledRef.current.delete(toggleKey);
        fetchCostData(true);
      }, 30000);

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
   * Get configured instance types for an ASG
   */
  const getInstanceTypeConfig = useCallback(async (region: string, asgName: string): Promise<string[]> => {
    if (!selectedAccountId) {
      throw new Error('No account selected');
    }
    try {
      console.log(`[SpotAutomation] Fetching instance type config for ${asgName} in ${region}`);
      const data = await apiClient.get(
        `/api/autospotting/asgs/${selectedAccountId}/instance-types?region=${region}&asgName=${encodeURIComponent(asgName)}`
      );
      return data.instanceTypes || [];
    } catch (err) {
      console.error('[SpotAutomation] Error fetching instance type config:', err);
      throw err;
    }
  }, [selectedAccountId]);

  /**
   * Save instance type configuration for an ASG
   */
  const saveInstanceTypeConfig = useCallback(async (region: string, asgName: string, instanceTypes: string[]): Promise<void> => {
    if (!selectedAccountId) {
      throw new Error('No account selected');
    }
    try {
      console.log(`[SpotAutomation] Saving instance type config for ${asgName} in ${region}:`, instanceTypes);
      await apiClient.put(
        `/api/autospotting/asgs/${selectedAccountId}/instance-types?region=${region}&asgName=${encodeURIComponent(asgName)}`,
        { instanceTypes }
      );
      console.log('[SpotAutomation] Instance type config saved successfully');
    } catch (err) {
      console.error('[SpotAutomation] Error saving instance type config:', err);
      throw err;
    }
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

  // Inject today's real-time savings into chart when backend returns $0 for today
  // but KPI data shows non-zero savings (backend getCostData() fails silently in history endpoint)
  useEffect(() => {
    if (costs.hourlyActualSavings <= 0 || historyData.length === 0) return;

    const todayStr = new Date().toISOString().substring(0, 10);
    const todayIdx = historyData.findIndex((d) => d.timestamp.startsWith(todayStr));

    if (todayIdx === -1) return;

    const todayPoint = historyData[todayIdx];
    if ((todayPoint.total_actual_savings || 0) === 0) {
      const updated = [...historyData];
      updated[todayIdx] = {
        ...todayPoint,
        total_actual_savings: costs.hourlyActualSavings,
        total_potential_savings: todayPoint.total_potential_savings || 0,
      };
      setHistoryData(updated);
    }
  }, [costs.hourlyActualSavings, historyData]);

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
    getInstanceTypeConfig,
    saveInstanceTypeConfig,
    generateDeploymentUrl,
  };
};
