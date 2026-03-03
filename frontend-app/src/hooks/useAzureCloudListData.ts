/**
 * useAzureCloudListData Hook
 *
 * Custom hook for fetching and managing Azure CloudList data
 */

import { useState, useEffect, useCallback, useRef } from 'react';
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

// Module-level cache — survives navigation (component unmount/remount)
const CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes

interface CacheEntry {
  data: AzureServiceGroup[];
  lastUpdated: string;
  fetchedAt: number;
}

const cache = new Map<string, CacheEntry>();

function getCacheKey(accountIds: string[]): string {
  return [...accountIds].sort().join(',');
}

function getCachedEntry(key: string): CacheEntry | null {
  const entry = cache.get(key);
  if (!entry) return null;
  if (Date.now() - entry.fetchedAt > CACHE_TTL_MS) {
    cache.delete(key);
    return null;
  }
  return entry;
}

export const useAzureCloudListData = ({
  accountIds,
  autoRefresh = false,
  refreshInterval = 300, // Default: 5 minutes
}: UseAzureCloudListDataOptions): UseAzureCloudListDataResult => {
  const cacheKey = getCacheKey(accountIds);
  const cached = accountIds.length > 0 ? getCachedEntry(cacheKey) : null;

  const [data, setData] = useState<AzureServiceGroup[]>(cached?.data ?? []);
  const [loading, setLoading] = useState<boolean>(!cached && accountIds.length > 0);
  const [error, setError] = useState<Error | null>(null);
  const [lastUpdated, setLastUpdated] = useState<string | null>(cached?.lastUpdated ?? null);

  // Track current accountIds string to detect changes
  const prevCacheKeyRef = useRef<string>(cacheKey);

  const fetchData = useCallback(
    async (forceRefresh: boolean = false) => {
      if (accountIds.length === 0) {
        setLoading(false);
        return;
      }

      const key = getCacheKey(accountIds);

      // Return cached data if valid and not forcing refresh
      if (!forceRefresh) {
        const entry = getCachedEntry(key);
        if (entry) {
          console.log('✅ Azure CloudList: Serving from cache for', key);
          setData(entry.data);
          setLastUpdated(entry.lastUpdated);
          setLoading(false);
          return;
        }
      }

      setLoading(true);
      setError(null);

      try {
        console.log('🔵 Azure CloudList Hook: Fetching resources for accounts:', accountIds);
        const result = await fetchAzureCloudListResources(accountIds, forceRefresh);
        const now = new Date().toISOString();

        // Save to module-level cache
        cache.set(key, { data: result, lastUpdated: now, fetchedAt: Date.now() });

        setData(result);
        setLastUpdated(now);
        console.log('✅ Azure CloudList Hook: Successfully fetched', result.length, 'service groups');
      } catch (err) {
        console.error('❌ Azure CloudList Hook: Error fetching data:', err);
        setError(err instanceof Error ? err : new Error('Unknown error'));
      } finally {
        setLoading(false);
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [accountIds.join(',')]
  );

  const refresh = useCallback(
    async (forceRefresh: boolean = false) => {
      console.log('🔵 Azure CloudList Hook: Manual refresh triggered, forceRefresh:', forceRefresh);
      await fetchData(forceRefresh);
    },
    [fetchData]
  );

  // On mount or accountIds change: use cache if available, otherwise fetch
  useEffect(() => {
    if (accountIds.length === 0) return;

    const key = getCacheKey(accountIds);
    const accountsChanged = key !== prevCacheKeyRef.current;
    prevCacheKeyRef.current = key;

    const entry = getCachedEntry(key);
    if (entry && !accountsChanged) {
      // Already populated state from initial useState — no fetch needed
      console.log('✅ Azure CloudList: Using cache on mount for', key);
      setData(entry.data);
      setLastUpdated(entry.lastUpdated);
      setLoading(false);
      return;
    }

    fetchData(false);
  }, [fetchData, accountIds.length]);

  // Auto-refresh interval
  useEffect(() => {
    if (!autoRefresh || accountIds.length === 0) return;

    const intervalMs = refreshInterval * 1000;
    const intervalId = setInterval(() => {
      console.log('🔵 Azure CloudList Hook: Auto-refresh triggered');
      fetchData(false);
    }, intervalMs);

    return () => clearInterval(intervalId);
  }, [autoRefresh, refreshInterval, fetchData]);

  return { data, loading, error, refresh, lastUpdated };
};
