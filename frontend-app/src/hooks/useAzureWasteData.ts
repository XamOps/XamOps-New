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
      open: true,
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

  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

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

  const loadWasteData = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setAllItems([]);
        setWasteGroups([]);
        setIsLoading(false);
        return;
      }

      if (isFetchingRef.current && !forceRefresh) return;
      isFetchingRef.current = true;

      if (forceRefresh) {
        setIsRefreshing(true);
        setIsLoading(true);
        clearCache();
      } else {
        const cached = loadFromCache();
        if (cached) {
          setAllItems(cached);
          setWasteGroups(groupByType(cached));
          setIsLoading(false);
          isFetchingRef.current = false;
          return;
        }
        setIsLoading(true);
      }

      try {
        const url = `/api/xamops/azure/waste?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;
        console.log('[Azure Waste] Fetching:', url);

        const res = await fetch(url, {
          credentials: 'include',
          headers: { Accept: 'application/json' },
        });

        if (!res.ok) throw new Error(`HTTP ${res.status}`);

        const items: AzureWasteItem[] = await res.json();
        const safeItems = Array.isArray(items) ? items : [];

        console.log('[Azure Waste] Received:', safeItems.length, 'items');

        setAllItems(safeItems);
        setWasteGroups(groupByType(safeItems));
        saveToCache(safeItems);
      } catch (err) {
        console.error('[Azure Waste] Fetch failed:', err);
        if (!forceRefresh) {
          setAllItems([]);
          setWasteGroups([]);
        }
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
        isFetchingRef.current = false;
      }
    },
    [selectedAccountId, loadFromCache, saveToCache, clearCache]
  );

  useEffect(() => {
    if (selectedAccountId) {
      const isDifferent = previousAccountRef.current !== selectedAccountId;
      if (isDifferent || !initializedRef.current) {
        previousAccountRef.current = selectedAccountId;
        initializedRef.current = true;
        loadWasteData(false);
      }
    } else {
      setAllItems([]);
      setWasteGroups([]);
      setIsLoading(false);
    }
  }, [selectedAccountId, loadWasteData]);

  return { wasteGroups, allItems, isLoading, isRefreshing, loadWasteData };
};
