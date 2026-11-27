import { useState, useEffect, useCallback, useRef } from 'react';
import type { AzureWasteItem, AzureWasteGroup } from '../types/azureWaste';

interface UseAzureWasteDataParams {
  selectedAccountId: string | null;
}

interface UseAzureWasteDataReturn {
  wasteGroups: AzureWasteGroup[];
  allItems: AzureWasteItem[];
  isLoading: boolean;
  isRefreshing: boolean;
  /** Set when the last load failed. A failure used to look exactly like "no waste found". */
  error: string | null;
  /** Checks that could not run in the scan being shown (from the X-Waste-Partial header). */
  partialChecks: string[];
  loadWasteData: (forceRefresh?: boolean) => Promise<void>;
}

const CACHE_DURATION = 24 * 60 * 60 * 1000; // 24 hours

function groupByType(items: AzureWasteItem[]): AzureWasteGroup[] {
  const map: Record<string, AzureWasteItem[]> = {};
  for (const item of items) {
    const key = item.resourceType || 'Other';
    if (!map[key]) map[key] = [];
    map[key].push(item);
  }
  return Object.entries(map)
    .map(([type, resources]) => ({
      type,
      resources,
      open: false,
      totalSavings: resources.reduce((sum, r) => sum + (r.monthlySavings ?? 0), 0),
    }))
    .sort((a, b) => b.totalSavings - a.totalSavings);
}

export const useAzureWasteData = ({
  selectedAccountId,
}: UseAzureWasteDataParams): UseAzureWasteDataReturn => {
  const [allItems, setAllItems] = useState<AzureWasteItem[]>([]);
  const [wasteGroups, setWasteGroups] = useState<AzureWasteGroup[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [partialChecks, setPartialChecks] = useState<string[]>([]);

  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);
  // Only the latest request may write state, so a slow earlier request (e.g. the initial load when
  // Scan Resources is clicked) cannot overwrite newer results or stop the spinner early.
  const requestSeq = useRef(0);
  const itemCountRef = useRef(0);

  const loadFromCache = useCallback((): AzureWasteItem[] | null => {
    if (!selectedAccountId) return null;
    try {
      const cached = localStorage.getItem(`azure_waste_${selectedAccountId}`);
      const expiry = localStorage.getItem(`azure_waste_${selectedAccountId}_expiry`);
      if (cached && expiry && Date.now() <= parseInt(expiry)) {
        const data = JSON.parse(cached) as AzureWasteItem[];
        console.log('[Azure Waste] Loaded from cache:', data.length, 'items');
        return data;
      }
    } catch (err) {
      console.error('[Azure Waste] Cache read failed:', err);
    }
    return null;
  }, [selectedAccountId]);

  const saveToCache = useCallback((items: AzureWasteItem[]) => {
    if (!selectedAccountId) return;
    try {
      localStorage.setItem(`azure_waste_${selectedAccountId}`, JSON.stringify(items));
      localStorage.setItem(
        `azure_waste_${selectedAccountId}_expiry`,
        (Date.now() + CACHE_DURATION).toString()
      );
    } catch (err) {
      console.error('[Azure Waste] Cache write failed:', err);
    }
  }, [selectedAccountId]);

  const clearCache = useCallback(() => {
    if (!selectedAccountId) return;
    localStorage.removeItem(`azure_waste_${selectedAccountId}`);
    localStorage.removeItem(`azure_waste_${selectedAccountId}_expiry`);
  }, [selectedAccountId]);

  const showItems = useCallback((items: AzureWasteItem[]) => {
    itemCountRef.current = items.length;
    setAllItems(items);
    setWasteGroups(groupByType(items));
  }, []);

  const loadWasteData = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        showItems([]);
        setIsLoading(false);
        return;
      }

      if (isFetchingRef.current && !forceRefresh) return;
      isFetchingRef.current = true;
      const requestId = ++requestSeq.current;

      let showingCached = false;
      if (forceRefresh) {
        setIsRefreshing(true);
        clearCache();
      } else {
        // Paint the browser's copy straight away, then ask the server anyway, as the AWS page does.
        // This returned on a cache hit without calling the server for up to 24 hours, so a newer
        // scan (or a disk deleted on the Disk Rightsizing page) never showed.
        const cached = loadFromCache();
        if (cached) {
          showItems(cached);
          setIsLoading(false);
          showingCached = true;
        }
      }
      // Keep the current rows on screen while re-scanning; skeletons only when there is nothing.
      if (!showingCached && itemCountRef.current === 0) setIsLoading(true);

      try {
        const url = `/api/xamops/azure/waste?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;
        console.log('[Azure Waste] Fetching:', url);

        const res = await fetch(url, {
          credentials: 'include',
          headers: { Accept: 'application/json' },
        });

        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const items: AzureWasteItem[] = await res.json();
        if (requestId !== requestSeq.current) return;
        const safeItems = Array.isArray(items) ? items : [];

        console.log('[Azure Waste] Received:', safeItems.length, 'items');

        showItems(safeItems);
        saveToCache(safeItems);
        setError(null);
        const partial = res.headers.get('X-Waste-Partial');
        setPartialChecks(partial ? partial.split(',').map(s => s.trim()).filter(Boolean) : []);
      } catch (err) {
        if (requestId !== requestSeq.current) return;
        console.error('[Azure Waste] Fetch failed:', err);
        setError(
          err instanceof Error && err.message.includes('503')
            ? 'Azure could not be scanned for this subscription. Check that its credentials are valid and its roles are assigned.'
            : err instanceof Error ? err.message : 'Failed to load waste data'
        );
        if (!forceRefresh && !showingCached) {
          showItems([]);
        }
      } finally {
        if (requestId === requestSeq.current) {
          setIsLoading(false);
          setIsRefreshing(false);
          isFetchingRef.current = false;
        }
      }
    },
    [selectedAccountId, loadFromCache, saveToCache, clearCache, showItems]
  );

  useEffect(() => {
    if (selectedAccountId) {
      const isDifferent = previousAccountRef.current !== selectedAccountId;
      if (isDifferent || !initializedRef.current) {
        previousAccountRef.current = selectedAccountId;
        initializedRef.current = true;
        if (isDifferent) {
          // Drop any load still running for the previous subscription: bumping the sequence makes
          // its response stale, and clearing the in-flight flag lets this subscription's load start
          // (it returned early before, and the old subscription's findings then filled the page).
          requestSeq.current++;
          isFetchingRef.current = false;
          // A new subscription starts empty rather than showing the previous one's findings.
          showItems([]);
          setError(null);
          setPartialChecks([]);
        }
        loadWasteData(false);
      }
    } else {
      showItems([]);
      setIsLoading(false);
    }
  }, [selectedAccountId, loadWasteData, showItems]);

  return { wasteGroups, allItems, isLoading, isRefreshing, error, partialChecks, loadWasteData };
};
