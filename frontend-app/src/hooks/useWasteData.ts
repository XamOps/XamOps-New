/**
 * useWasteData Hook
 *
 * Custom hook to fetch and manage waste management data
 */

import { useState, useEffect, useCallback } from 'react';
import type { WastedResource } from '../types/waste';

interface UseWasteDataOptions {
  selectedAccountId: string | null;
}

interface UseWasteDataReturn {
  wastedResources: WastedResource[];
  isLoading: boolean;
  isRefreshing: boolean;
  error: Error | null;
  loadWastedResources: (forceRefresh?: boolean) => Promise<void>;
}

export const useWasteData = ({
  selectedAccountId,
}: UseWasteDataOptions): UseWasteDataReturn => {
  const [wastedResources, setWastedResources] = useState<WastedResource[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);

  // Load waste data with caching
  const loadWastedResources = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        console.log('[Waste] No account selected, skipping waste data load.');
        setIsLoading(false);
        return;
      }

      // Set loading states
      if (forceRefresh) {
        setIsRefreshing(true);
      } else if (wastedResources.length === 0) {
        setIsLoading(true);
      }

      try {
        // Check cache first (only if not forcing refresh)
        const cacheKey = `waste_data_${selectedAccountId}`;
        if (!forceRefresh) {
          const cachedData = sessionStorage.getItem(cacheKey);
          if (cachedData) {
            try {
              const parsed = JSON.parse(cachedData);
              setWastedResources(parsed);
              setIsLoading(false);
              console.log('[Waste] Loaded cached data:', parsed.length, 'resources');
            } catch (e) {
              console.error('[Waste] Failed to parse cached data:', e);
            }
          }
        }

        // Fetch fresh data
        console.log('[Waste] Fetching waste data for account:', selectedAccountId, 'forceRefresh:', forceRefresh);

        const response = await fetch(
          `/api/xamops/waste?accountIds=${selectedAccountId}&forceRefresh=${forceRefresh}`,
          {
            credentials: 'include',
          }
        );

        if (!response.ok) {
          const text = await response.text();
          throw new Error(`HTTP error! status: ${response.status}, body: ${text}`);
        }

        const data = await response.json();
        const resources = Array.isArray(data) ? data : [];

        setWastedResources(resources);
        console.log('[Waste] Loaded', resources.length, 'waste resources');

        // Cache the data
        try {
          sessionStorage.setItem(cacheKey, JSON.stringify(resources));
        } catch (e) {
          console.error('[Waste] Failed to cache data:', e);
        }
      } catch (err) {
        console.error('[Waste] Failed to load waste data:', err);
        setError(err instanceof Error ? err : new Error('Failed to load waste data'));
        setWastedResources([]);
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId, wastedResources.length]
  );

  // Initial load
  useEffect(() => {
    if (selectedAccountId) {
      loadWastedResources(false);
    }
  }, [selectedAccountId, loadWastedResources]);

  return {
    wastedResources,
    isLoading,
    isRefreshing,
    error,
    loadWastedResources,
  };
};
