/**
 * useGcpWasteData Hook
 *
 * Custom hook for fetching and managing GCP waste management data
 * Handles fetching waste reports with caching and force refresh
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type { GcpWasteResource } from '../types/gcpWaste';

interface UseGcpWasteDataParams {
  selectedAccountId: string | null;
}

interface UseGcpWasteDataReturn {
  wasteItems: GcpWasteResource[];
  isLoading: boolean;
  isRefreshing: boolean;
  isPartial: boolean;
  loadWasteReport: (forceRefresh?: boolean) => Promise<void>;
}

const CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

export const useGcpWasteData = ({
  selectedAccountId,
}: UseGcpWasteDataParams): UseGcpWasteDataReturn => {
  // State for data and loading
  const [wasteItems, setWasteItems] = useState<GcpWasteResource[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isPartial, setIsPartial] = useState(false);

  // Refs for stable execution
  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

  /**
   * Load from cache
   */
  const loadFromCache = useCallback((): GcpWasteResource[] | null => {
    if (!selectedAccountId) return null;

    try {
      const cached = localStorage.getItem(`waste_report_${selectedAccountId}`);
      const cacheExpiry = localStorage.getItem(`waste_report_${selectedAccountId}_expiry`);

      if (cached && cacheExpiry) {
        const isCacheExpired = Date.now() > parseInt(cacheExpiry);
        if (!isCacheExpired) {
          console.log('[GCP Waste] Loaded from cache:', JSON.parse(cached).length, 'items');
          return JSON.parse(cached);
        }
      }
    } catch (err) {
      console.error('[GCP Waste] Failed to load from cache:', err);
    }

    return null;
  }, [selectedAccountId]);

  /**
   * Save to cache
   */
  const saveToCache = useCallback((data: GcpWasteResource[]) => {
    if (!selectedAccountId) return;

    try {
      localStorage.setItem(`waste_report_${selectedAccountId}`, JSON.stringify(data));
      const expiry = Date.now() + CACHE_DURATION;
      localStorage.setItem(`waste_report_${selectedAccountId}_expiry`, expiry.toString());
    } catch (err) {
      console.error('[GCP Waste] Failed to save to cache:', err);
    }
  }, [selectedAccountId]);

  /**
   * Clear cache
   */
  const clearCache = useCallback(() => {
    if (!selectedAccountId) return;

    localStorage.removeItem(`waste_report_${selectedAccountId}`);
    localStorage.removeItem(`waste_report_${selectedAccountId}_expiry`);
    console.log('[GCP Waste] Cache cleared');
  }, [selectedAccountId]);

  /**
   * Load waste report from API
   */
  const loadWasteReport = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setWasteItems([]);
      setIsLoading(false);
      return;
    }

    // specific check to prevent double-fetching if already in progress and not forced
    if (isFetchingRef.current && !forceRefresh) {
      console.log('[GCP Waste] Fetch already in progress, skipping...');
      return;
    }

    isFetchingRef.current = true;

    if (forceRefresh) {
      setIsRefreshing(true);
      setIsLoading(true);
      clearCache();
      console.log('[GCP Waste] Force refresh - clearing cache');
    } else {
      // Try to load from cache first
      const cachedData = loadFromCache();
      if (cachedData) {
        setWasteItems(cachedData);
        setIsLoading(false);
        isFetchingRef.current = false;
        return;
      }
      setIsLoading(true);
    }

    try {
      console.log(`[GCP Waste] Fetching waste report for account: ${selectedAccountId}, forceRefresh: ${forceRefresh}`);

      const response = await fetch(
        `/api/xamops/gcp/optimization/waste-report?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`
      );

      if (!response.ok) {
        throw new Error(`HTTP error! status: ${response.status}`);
      }

      const data = await response.json();

      // Backend now returns { items: [...], partial: boolean }
      const rawItems = Array.isArray(data) ? data : (data?.items ?? []);
      const partial: boolean = data?.partial ?? false;
      console.log('[GCP Waste] Waste report received:', rawItems.length, 'items, partial:', partial);

      const wasteData = rawItems.map((item: GcpWasteResource) => ({ ...item, detailsOpen: false }));

      setWasteItems(wasteData);
      setIsPartial(partial);
      saveToCache(wasteData);
    } catch (err) {
      console.error('[GCP Waste] Failed to load GCP waste report:', err);
      if (!isRefreshing) setWasteItems([]);
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
      isFetchingRef.current = false;
      console.log('[GCP Waste] Fetch complete');
    }
  }, [selectedAccountId, loadFromCache, saveToCache, clearCache]);

  // Load data when account changes
  useEffect(() => {
    // Guard for initial mount if needed, or strict mode double-effect
    // We only want to auto-load if account changed or it's the very first valid account load
    if (selectedAccountId) {
      const isDifferentAccount = previousAccountRef.current !== selectedAccountId;

      if (isDifferentAccount || !initializedRef.current) {
        console.log('[GCP Waste] Account changed or Initial load, loading waste report...');
        previousAccountRef.current = selectedAccountId;
        initializedRef.current = true;
        loadWasteReport(false);
      }
    } else {
      setWasteItems([]);
      setIsLoading(false);
    }
  }, [selectedAccountId, loadWasteReport]);

  return {
    wasteItems,
    isLoading,
    isRefreshing,
    isPartial,
    loadWasteReport,
  };
};
