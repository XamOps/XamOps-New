/**
 * useGcpRightsizingData Hook
 *
 * Custom hook for fetching and managing GCP rightsizing recommendations
 * Handles fetching recommendations with caching and force refresh
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type { GcpRightsizingRecommendation } from '../types/gcpRightsizing';

interface UseGcpRightsizingDataParams {
  selectedAccountId: string | null;
}

interface UseGcpRightsizingDataReturn {
  recommendations: GcpRightsizingRecommendation[];
  isLoading: boolean;
  isRefreshing: boolean;
  loadRecommendations: (forceRefresh?: boolean) => Promise<void>;
}

const CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

export const useGcpRightsizingData = ({
  selectedAccountId,
}: UseGcpRightsizingDataParams): UseGcpRightsizingDataReturn => {
  // State for data and loading
  const [recommendations, setRecommendations] = useState<GcpRightsizingRecommendation[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Refs for stable execution
  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

  /**
   * Load from cache
   */
  const loadFromCache = useCallback((): GcpRightsizingRecommendation[] | null => {
    if (!selectedAccountId) return null;

    try {
      const cached = localStorage.getItem(`rightsizing_recs_${selectedAccountId}`);
      const cacheExpiry = localStorage.getItem(`rightsizing_recs_${selectedAccountId}_expiry`);

      if (cached && cacheExpiry) {
        const isCacheExpired = Date.now() > parseInt(cacheExpiry);
        if (!isCacheExpired) {
          console.log('[GCP Rightsizing] Loaded from cache:', JSON.parse(cached).length, 'items');
          return JSON.parse(cached);
        }
      }
    } catch (err) {
      console.error('[GCP Rightsizing] Failed to load from cache:', err);
    }

    return null;
  }, [selectedAccountId]);

  /**
   * Save to cache
   */
  const saveToCache = useCallback((data: GcpRightsizingRecommendation[]) => {
    if (!selectedAccountId) return;

    try {
      localStorage.setItem(`rightsizing_recs_${selectedAccountId}`, JSON.stringify(data));
      const expiry = Date.now() + CACHE_DURATION;
      localStorage.setItem(`rightsizing_recs_${selectedAccountId}_expiry`, expiry.toString());
    } catch (err) {
      console.error('[GCP Rightsizing] Failed to save to cache:', err);
    }
  }, [selectedAccountId]);

  /**
   * Clear cache
   */
  const clearCache = useCallback(() => {
    if (!selectedAccountId) return;

    localStorage.removeItem(`rightsizing_recs_${selectedAccountId}`);
    localStorage.removeItem(`rightsizing_recs_${selectedAccountId}_expiry`);
    console.log('[GCP Rightsizing] Cache cleared');
  }, [selectedAccountId]);

  /**
   * Load recommendations from API
   */
  const loadRecommendations = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setRecommendations([]);
      setIsLoading(false);
      return;
    }

    // Prevent double-fetching if already in progress and not forced
    if (isFetchingRef.current && !forceRefresh) {
      console.log('[GCP Rightsizing] Fetch already in progress, skipping...');
      return;
    }

    isFetchingRef.current = true;

    if (forceRefresh) {
      setIsRefreshing(true);
      setIsLoading(true);
      clearCache();
      console.log('[GCP Rightsizing] Force refresh - clearing cache');
    } else {
      // Try to load from cache first
      const cachedData = loadFromCache();
      if (cachedData) {
        setRecommendations(cachedData);
        setIsLoading(false);
        isFetchingRef.current = false;
        return;
      }
      setIsLoading(true);
    }

    try {
      console.log(`[GCP Rightsizing] Fetching recommendations for account: ${selectedAccountId}, forceRefresh: ${forceRefresh}`);

      const response = await fetch(
        `/api/xamops/gcp/rightsizing/recommendations?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`
      );

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();
      console.log('[GCP Rightsizing] Recommendations received:', Array.isArray(data) ? data.length : 0, 'items');

      const recsData = Array.isArray(data) ? data : [];

      setRecommendations(recsData);
      saveToCache(recsData);
    } catch (err) {
      console.error('[GCP Rightsizing] Failed to load GCP rightsizing recommendations:', err);
      if (!isRefreshing) setRecommendations([]);
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
      isFetchingRef.current = false;
      console.log('[GCP Rightsizing] Fetch complete');
    }
  }, [selectedAccountId, loadFromCache, saveToCache, clearCache]);

  // Load data when account changes
  useEffect(() => {
    if (selectedAccountId) {
      const isDifferentAccount = previousAccountRef.current !== selectedAccountId;

      if (isDifferentAccount || !initializedRef.current) {
        console.log('[GCP Rightsizing] Account changed or Initial load, loading recommendations...');
        previousAccountRef.current = selectedAccountId;
        initializedRef.current = true;
        loadRecommendations(false);
      }
    } else {
      setRecommendations([]);
      setIsLoading(false);
    }
  }, [selectedAccountId, loadRecommendations]);

  return {
    recommendations,
    isLoading,
    isRefreshing,
    loadRecommendations,
  };
};
