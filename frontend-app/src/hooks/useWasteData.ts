/**
 * useWasteData Hook
 *
 * Custom hook to fetch and manage waste management data
 */

import { useState, useEffect, useCallback, useRef } from 'react';
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
  removeResource: (resourceId: string) => void;
}

export const useWasteData = ({
  selectedAccountId,
}: UseWasteDataOptions): UseWasteDataReturn => {
  const [wastedResources, setWastedResources] = useState<WastedResource[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  const loadWastedResources = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        console.log('[Waste] No account selected, skipping waste data load.');
        setIsLoading(false);
        return;
      }

      // Cancel any in-flight request before starting a new one
      abortControllerRef.current?.abort();
      abortControllerRef.current = new AbortController();
      const { signal } = abortControllerRef.current;

      const cacheKey = `waste_data_${selectedAccountId}`;

      if (forceRefresh) {
        // Clear browser cache so the UI never serves stale data after a refresh
        sessionStorage.removeItem(cacheKey);
        setIsRefreshing(true);
      } else {
        // Serve cached data instantly while fresh data loads in background
        const cachedData = sessionStorage.getItem(cacheKey);
        if (cachedData) {
          try {
            setWastedResources(JSON.parse(cachedData));
            setIsLoading(false);
            console.log('[Waste] Served session cache while fetching fresh data');
          } catch (e) {
            console.error('[Waste] Failed to parse cached data:', e);
          }
        } else {
          setIsLoading(true);
        }
      }

      try {
        console.log('[Waste] Fetching waste data for account:', selectedAccountId, 'forceRefresh:', forceRefresh);

        const response = await fetch(
          `/api/xamops/waste?accountIds=${selectedAccountId}&forceRefresh=${forceRefresh}`,
          { credentials: 'include', signal }
        );

        if (!response.ok) {
          const text = await response.text();
          throw new Error(`HTTP error! status: ${response.status}, body: ${text}`);
        }

        const data = await response.json();
        const resources = Array.isArray(data) ? data : [];

        setWastedResources(resources);
        console.log('[Waste] Loaded', resources.length, 'waste resources');

        try {
          sessionStorage.setItem(cacheKey, JSON.stringify(resources));
        } catch (e) {
          console.error('[Waste] Failed to cache data:', e);
        }
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        console.error('[Waste] Failed to load waste data:', err);
        setError(err instanceof Error ? err : new Error('Failed to load waste data'));
        setWastedResources([]);
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId]
  );

  const removeResource = useCallback(
    (resourceId: string) => {
      setWastedResources((prev) => {
        const updated = prev.filter((r) => r.resourceId !== resourceId);
        if (selectedAccountId) {
          try {
            sessionStorage.setItem(`waste_data_${selectedAccountId}`, JSON.stringify(updated));
          } catch (e) {
            // ignore cache errors
          }
        }
        return updated;
      });
    },
    [selectedAccountId]
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
    removeResource,
  };
};
