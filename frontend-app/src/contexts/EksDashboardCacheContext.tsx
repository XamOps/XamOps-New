/**
 * EKS Dashboard Cache Context
 *
 * Provides frontend caching for EKS dashboard data to prevent re-fetching
 * when navigating between tabs/pages in XamOps.
 *
 * Features:
 * - Caches dashboard data by cluster key (clusterName-accountId-region)
 * - Persists across navigation (component unmounts/remounts)
 * - Auto-expires cache after 5 minutes (configurable)
 * - Reduces API calls and improves UX
 */

import React, { createContext, useContext, useState, useCallback, ReactNode } from 'react';
import { EksDashboard } from '../types/eksTypes';

interface CacheEntry {
  data: EksDashboard;
  timestamp: number;
  lastSyncedAt: number;
}

interface EksDashboardCacheContextType {
  getCachedDashboard: (clusterName: string, accountId: string, region: string) => CacheEntry | null;
  setCachedDashboard: (clusterName: string, accountId: string, region: string, data: EksDashboard, lastSyncedAt: number) => void;
  clearCache: (clusterName?: string, accountId?: string, region?: string) => void;
  isCacheValid: (clusterName: string, accountId: string, region: string, maxAgeMs?: number) => boolean;
}

const EksDashboardCacheContext = createContext<EksDashboardCacheContextType | null>(null);

// Default cache TTL: 5 minutes
const DEFAULT_CACHE_TTL_MS = 5 * 60 * 1000;

const getCacheKey = (clusterName: string, accountId: string, region: string): string => {
  return `${clusterName}-${accountId}-${region}`;
};

export const EksDashboardCacheProvider: React.FC<{ children: ReactNode }> = ({ children }) => {
  const [cache, setCache] = useState<Map<string, CacheEntry>>(new Map());

  const getCachedDashboard = useCallback((clusterName: string, accountId: string, region: string): CacheEntry | null => {
    const key = getCacheKey(clusterName, accountId, region);
    const entry = cache.get(key);

    if (!entry) {
      return null;
    }

    return entry;
  }, [cache]);

  const setCachedDashboard = useCallback((
    clusterName: string,
    accountId: string,
    region: string,
    data: EksDashboard,
    lastSyncedAt: number
  ) => {
    const key = getCacheKey(clusterName, accountId, region);

    setCache(prev => {
      const newCache = new Map(prev);
      newCache.set(key, {
        data,
        timestamp: Date.now(),
        lastSyncedAt
      });
      return newCache;
    });
  }, []);

  const clearCache = useCallback((clusterName?: string, accountId?: string, region?: string) => {
    if (clusterName && accountId && region) {
      // Clear specific cache entry
      const key = getCacheKey(clusterName, accountId, region);
      setCache(prev => {
        const newCache = new Map(prev);
        newCache.delete(key);
        return newCache;
      });
    } else {
      // Clear all cache
      setCache(new Map());
    }
  }, []);

  const isCacheValid = useCallback((
    clusterName: string,
    accountId: string,
    region: string,
    maxAgeMs: number = DEFAULT_CACHE_TTL_MS
  ): boolean => {
    const key = getCacheKey(clusterName, accountId, region);
    const entry = cache.get(key);

    if (!entry) {
      return false;
    }

    const age = Date.now() - entry.timestamp;
    return age < maxAgeMs;
  }, [cache]);

  return (
    <EksDashboardCacheContext.Provider value={{
      getCachedDashboard,
      setCachedDashboard,
      clearCache,
      isCacheValid
    }}>
      {children}
    </EksDashboardCacheContext.Provider>
  );
};

export const useEksDashboardCache = (): EksDashboardCacheContextType => {
  const context = useContext(EksDashboardCacheContext);
  if (!context) {
    throw new Error('useEksDashboardCache must be used within an EksDashboardCacheProvider');
  }
  return context;
};

export default EksDashboardCacheContext;
