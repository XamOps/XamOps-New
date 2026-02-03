/**
 * useAzureCloudListData Hook
 *
 * Custom hook for fetching and managing Azure CloudList data
 */

import { useState, useEffect, useCallback } from 'react';
import { AzureServiceGroup } from '../types/azureCloudList';
import { fetchAzureCloudListResources } from '../api/azureCloudListApi';

interface UseAzureCloudListDataOptions {
  accountIds: string[];
  autoRefresh?: boolean;
  refreshInterval?: number; // in seconds
}

interface UseAzureCloudListDataResult {
  data: AzureServiceGroup[];
  loading: boolean;
  error: Error | null;
  refresh: (forceRefresh?: boolean) => Promise<void>;
  lastUpdated: string | null;
}

export const useAzureCloudListData = ({
  accountIds,
  autoRefresh = false,
  refreshInterval = 300, // Default: 5 minutes
}: UseAzureCloudListDataOptions): UseAzureCloudListDataResult => {
  const [data, setData] = useState<AzureServiceGroup[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<Error | null>(null);
  const [lastUpdated, setLastUpdated] = useState<string | null>(null);

  /**
   * Fetch data from API
   */
  const fetchData = useCallback(
    async (forceRefresh: boolean = false) => {
      if (accountIds.length === 0) {
        setLoading(false);
        return;
      }

      setLoading(true);
      setError(null);

      try {
        console.log('🔵 Azure CloudList Hook: Fetching resources for accounts:', accountIds);
        const result = await fetchAzureCloudListResources(accountIds, forceRefresh);

        setData(result);
        setLastUpdated(new Date().toISOString());
        console.log('✅ Azure CloudList Hook: Successfully fetched', result.length, 'service groups');
      } catch (err) {
        console.error('❌ Azure CloudList Hook: Error fetching data:', err);
        setError(err instanceof Error ? err : new Error('Unknown error'));
      } finally {
        setLoading(false);
      }
    },
    [accountIds]
  );

  /**
   * Refresh handler
   */
  const refresh = useCallback(
    async (forceRefresh: boolean = false) => {
      console.log('🔵 Azure CloudList Hook: Manual refresh triggered, forceRefresh:', forceRefresh);
      await fetchData(forceRefresh);
    },
    [fetchData]
  );

  /**
   * Initial load
   */
  useEffect(() => {
    console.log('🔵 Azure CloudList Hook: Initial load for accounts:', accountIds);
    fetchData(false);
  }, [fetchData]);

  /**
   * Auto-refresh interval
   */
  useEffect(() => {
    if (!autoRefresh || accountIds.length === 0) return;

    const intervalMs = refreshInterval * 1000;
    console.log(`🔵 Azure CloudList Hook: Setting up auto-refresh every ${refreshInterval}s`);

    const intervalId = setInterval(() => {
      console.log('🔵 Azure CloudList Hook: Auto-refresh triggered');
      fetchData(false);
    }, intervalMs);

    return () => {
      console.log('🔵 Azure CloudList Hook: Clearing auto-refresh interval');
      clearInterval(intervalId);
    };
  }, [autoRefresh, refreshInterval, accountIds.length, fetchData]);

  return {
    data,
    loading,
    error,
    refresh,
    lastUpdated,
  };
};
