/**
 * useAzureCloudListData Hook
 *
 * Custom hook for fetching and managing Azure CloudList data.
 * Uses stale-while-revalidate: cached data (even stale) is shown immediately,
 * skeleton only appears on the very first fetch when there is no data at all.
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
  loading: boolean;       // true only when NO data exists yet (shows skeleton)
  isRefreshing: boolean;  // true during a background refresh (data already visible)
  error: Error | null;
  refresh: (forceRefresh?: boolean) => Promise<void>;
  lastUpdated: string | null;
}

// Module-level cache — survives navigation (component unmount/remount)
// TTL matches the backend Redis TTL (60 min). Stale entries are kept in the Map
// and served immediately; a background refresh is triggered if they are stale.
const CACHE_TTL_MS = 60 * 60 * 1000; // 60 minutes

interface CacheEntry {
  data: AzureServiceGroup[];
  lastUpdated: string;
  fetchedAt: number;
}

const cache = new Map<string, CacheEntry>();

function getCacheKey(accountIds: string[]): string {
  return [...accountIds].sort().join(',');
}

// Always returns the entry if it exists — never auto-deletes stale entries.
function getCachedEntry(key: string): CacheEntry | null {
  return cache.get(key) ?? null;
}

// Returns true when the entry is still within the freshness window.
function isEntryFresh(entry: CacheEntry): boolean {
  return Date.now() - entry.fetchedAt <= CACHE_TTL_MS;
}

export const useAzureCloudListData = ({
  accountIds,
  autoRefresh = false,
  refreshInterval = 300,
}: UseAzureCloudListDataOptions): UseAzureCloudListDataResult => {
  const cacheKey = getCacheKey(accountIds);
  const cached = accountIds.length > 0 ? getCachedEntry(cacheKey) : null;

  // Initialise state from cache so data is available on the very first render
  const [data, setData] = useState<AzureServiceGroup[]>(cached?.data ?? []);
  // loading=true only when we have absolutely no data (first ever fetch → skeleton)
  const [loading, setLoading] = useState<boolean>(!cached && accountIds.length > 0);
  // isRefreshing=true during a background fetch while stale data is visible
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);
  const [lastUpdated, setLastUpdated] = useState<string | null>(cached?.lastUpdated ?? null);

  const prevCacheKeyRef = useRef<string>(cacheKey);

  const fetchData = useCallback(
    async (forceRefresh: boolean = false) => {
      if (accountIds.length === 0) {
        setLoading(false);
        return;
      }

      const key = getCacheKey(accountIds);

      // Return fresh cache immediately (no network call)
      if (!forceRefresh) {
        const entry = getCachedEntry(key);
        if (entry && isEntryFresh(entry)) {
          setData(entry.data);
          setLastUpdated(entry.lastUpdated);
          setLoading(false);
          setIsRefreshing(false);
          return;
        }
      }

      // Decide whether to show skeleton or background-refresh indicator
      const hasCachedData = getCachedEntry(key) !== null;
      if (hasCachedData) {
        // We already have data to show — do a silent background refresh
        setIsRefreshing(true);
      } else {
        // No data at all — show skeleton
        setLoading(true);
      }
      setError(null);

      try {
        console.log('🔵 Azure CloudList: Fetching resources for accounts:', accountIds);
        const result = await fetchAzureCloudListResources(accountIds, forceRefresh);
        const now = new Date().toISOString();
        cache.set(key, { data: result, lastUpdated: now, fetchedAt: Date.now() });
        setData(result);
        setLastUpdated(now);
        console.log('✅ Azure CloudList: Fetched', result.length, 'service groups');
      } catch (err) {
        console.error('❌ Azure CloudList: Error fetching data:', err);
        setError(err instanceof Error ? err : new Error('Unknown error'));
      } finally {
        setLoading(false);
        setIsRefreshing(false);
      }
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [accountIds.join(',')]
  );

  const refresh = useCallback(
    async (forceRefresh: boolean = false) => {
      console.log('🔵 Azure CloudList: Manual refresh triggered, forceRefresh:', forceRefresh);
      await fetchData(forceRefresh);
    },
    [fetchData]
  );

  // On mount or accountIds change
  useEffect(() => {
    if (accountIds.length === 0) return;

    const key = getCacheKey(accountIds);
    const accountsChanged = key !== prevCacheKeyRef.current;
    prevCacheKeyRef.current = key;

    const entry = getCachedEntry(key);
    if (entry && !accountsChanged) {
      // Show cached data immediately (fresh or stale)
      setData(entry.data);
      setLastUpdated(entry.lastUpdated);
      setLoading(false);

      // If stale, kick off a silent background refresh
      if (!isEntryFresh(entry)) {
        console.log('🔄 Azure CloudList: Stale cache — background refresh for', key);
        fetchData(false);
      }
      return;
    }

    fetchData(false);
  }, [fetchData, accountIds.length]);

  // Optional auto-refresh interval
  useEffect(() => {
    if (!autoRefresh || accountIds.length === 0) return;
    const intervalMs = refreshInterval * 1000;
    const intervalId = setInterval(() => fetchData(false), intervalMs);
    return () => clearInterval(intervalId);
  }, [autoRefresh, refreshInterval, fetchData]);

  return { data, loading, isRefreshing, error, refresh, lastUpdated };
};
