import { useState, useEffect, useCallback, useRef } from 'react';
import {
  AzureDashboardData,
  AzureDashboardLoadingState,
  AzureDashboardError,
} from '../types/azureDashboard';
import { fetchAzureDashboardData } from '../api/azureDashboardApi';

interface UseAzureDashboardDataProps {
  accountIds: string[];
  autoRefresh?: boolean;
  refreshInterval?: number; // in seconds
}

interface UseAzureDashboardDataReturn {
  data: AzureDashboardData | null;
  /** True only when there is nothing to render yet — drives the full-page skeleton. */
  loading: AzureDashboardLoadingState;
  /**
   * True while any request is in flight, including a background revalidation behind data that
   * is already on screen.
   *
   * Separate from {@link loading} because the two need opposite treatment. The page used to
   * drive its Refresh button from `loading.global`, so a routine snapshot read — the cheapest
   * call this page makes — relabelled the button "Refreshing…", which is what made it look
   * like opening the dashboard triggered a refresh. It did not: nothing calls the live Azure
   * path except the button itself.
   */
  isRefreshing: boolean;
  error: AzureDashboardError | null;
  refresh: () => Promise<void>;
  clearError: () => void;
  lastUpdated: string | null;
}

const CACHE_KEY_PREFIX = 'azure_dashboard_';
const DEFAULT_REFRESH_INTERVAL = 60; // 60 seconds

/**
 * The sessionStorage copy is for painting immediately, never for deciding not to fetch.
 *
 * Stale-while-revalidate: a remount paints whatever was last seen, at any age, and a request
 * goes out regardless. So navigating back to this page is instant and still ends up current.
 *
 * Both previous shapes were wrong in opposite directions. Originally the cache had no age
 * limit and `loadDashboardData(false)` returned it *instead of* calling the API — and since
 * the 60-second poll also called `loadDashboardData(false)`, the dashboard made exactly one
 * request per browser session and could not see a backend refresh at all. Capping the age at
 * 30s fixed the freshness but broke the paint: past the cap a remount had nothing to render,
 * so returning to the page blanked every KPI to zero and spun until the fetch landed, which
 * read as "it refreshes every time I open it".
 *
 * Painting from a stale copy while revalidating gets both, and needs no cap at all — which is
 * why there is no longer a TTL constant here.
 */
interface CachedEnvelope {
  data: AzureDashboardData;
  cachedAt: number;
}

export const useAzureDashboardData = ({
  accountIds,
  autoRefresh = true,
  refreshInterval = DEFAULT_REFRESH_INTERVAL,
}: UseAzureDashboardDataProps): UseAzureDashboardDataReturn => {
  const [data, setData] = useState<AzureDashboardData | null>(null);
  const [loading, setLoading] = useState<AzureDashboardLoadingState>({
    global: false,
    kpi: false,
    inventory: false,
    cost: false,
    optimization: false,
    map: false,
  });
  const [error, setError] = useState<AzureDashboardError | null>(null);
  const [lastUpdated, setLastUpdated] = useState<string | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);

  /**
   * Which account selection the data currently on screen belongs to.
   *
   * Copied from the AWS dashboard hook (`useDashboardData`), which has had this right all along:
   * the skeleton decision is not "do we have data" but "does the data we have belong to what is
   * being asked for". A refresh of the same selection swaps figures in place; switching account
   * shows skeletons, because leaving one subscription's numbers on screen under another's name is
   * worse than a brief skeleton.
   *
   * A boolean has-data flag was tried first and cannot express the second case. AWS also notes a
   * reason this matters beyond aesthetics: re-showing skeletons unmounts whatever is open,
   * including the widget customizer, on every refresh cycle.
   *
   * A ref, not state, so `loadDashboardData` does not depend on it — the auto-refresh effect
   * depends on that callback, so a dependency here would tear down and rebuild the 60-second
   * interval on every poll.
   */
  const loadedSelectionRef = useRef<string | null>(null);

  /** Stable key for a selection, order-independent, matching the AWS hook. */
  const selectionKey = useCallback((ids: string[]) => [...ids].sort().join(','), []);

  const refreshTimerRef = useRef<NodeJS.Timeout | null>(null);
  const isFetchingRef = useRef(false);
  const previousAccountIdsRef = useRef<string[]>([]);
  const initializedRef = useRef(false);

  // Get cache key
  const getCacheKey = useCallback(() => {
    return `${CACHE_KEY_PREFIX}${accountIds[0]}`;
  }, [accountIds]);

  // Load from cache, at any age. Used to paint immediately, never to skip a fetch.
  const loadFromCache = useCallback((): AzureDashboardData | null => {
    try {
      const cacheKey = getCacheKey();
      const cached = sessionStorage.getItem(cacheKey);
      if (!cached) return null;

      const envelope = JSON.parse(cached) as CachedEnvelope | AzureDashboardData;

      // Entries written before this envelope existed have no cachedAt. Still usable for
      // painting — the shape of the payload is unchanged — so read them rather than
      // discarding and blanking the page.
      if ('cachedAt' in envelope && typeof envelope.cachedAt === 'number') {
        setLastUpdated(envelope.data?.lastUpdated || new Date().toISOString());
        return envelope.data;
      }
      const legacy = envelope as AzureDashboardData;
      setLastUpdated(legacy.lastUpdated || new Date().toISOString());
      return legacy;
    } catch (err) {
      console.error('Failed to load from cache:', err);
    }
    return null;
  }, [getCacheKey]);

  // Save to cache
  const saveToCache = useCallback(
    (dashboardData: AzureDashboardData) => {
      try {
        const cacheKey = getCacheKey();
        const envelope: CachedEnvelope = { data: dashboardData, cachedAt: Date.now() };
        sessionStorage.setItem(cacheKey, JSON.stringify(envelope));
      } catch (err) {
        console.error('Failed to save to cache:', err);
      }
    },
    [getCacheKey]
  );

  // Load dashboard data.
  //
  // forceRefresh     -> ask the backend for a live Azure fetch (slow, expensive)
  // paintFromCache   -> render the last known payload first, then revalidate. Used on mount
  //                     and on remount so the page is never blank while a request is in
  //                     flight. The request still happens either way.
  const loadDashboardData = useCallback(
    async (forceRefresh: boolean = false, paintFromCache: boolean = false) => {
      console.log('🔷 Azure Dashboard: loadDashboardData called', {
        accountIds,
        forceRefresh,
        paintFromCache,
      });

      if (accountIds.length === 0) {
        console.log('🔷 Azure Dashboard: No account IDs, clearing data');
        setData(null);
        loadedSelectionRef.current = null;
        setError(null);
        return;
      }

      const key = selectionKey(accountIds);
      // Skeletons only when what is on screen does not belong to this selection — first load or
      // an account switch. Same rule as the AWS dashboard.
      const showSkeletons = loadedSelectionRef.current !== key;

      if (isFetchingRef.current && !forceRefresh) {
        console.log('🔷 Azure Dashboard: Already loading, skipping');
        return;
      }

      // Paint before fetching, not instead of fetching.
      //
      // This is what stops a remount showing zeros. Previously the cache read returned early,
      // so the page was either instantly complete or — once the entry aged out — completely
      // blank with every section spinning.
      // Paint the last known payload before fetching, so a remount is never blank. This is the
      // one thing Azure needs that AWS does not: the AWS dashboard holds its data in a hook that
      // outlives navigation, whereas this page remounts and starts empty.
      let painted = false;
      if (paintFromCache && showSkeletons) {
        const cachedData = loadFromCache();
        if (cachedData) {
          console.log('🔷 Azure Dashboard: Painted from session cache, revalidating');
          setData(cachedData);
          loadedSelectionRef.current = key;
          painted = true;
        }
      }

      isFetchingRef.current = true;
      // All section flags move together, as the AWS hook does it. Setting only `global` was not
      // enough: the page passes loading.kpi / loading.cost / loading.inventory to its sections, so
      // a `global`-only flag left every section rendering the previous account's figures during a
      // switch. Moving them together is also what makes clearing `data` unnecessary — the
      // skeletons cover the gap, and the payload is replaced when the fetch lands.
      const skeletons = showSkeletons && !painted;
      setLoading({
        global: skeletons,
        kpi: skeletons,
        inventory: skeletons,
        cost: skeletons,
        optimization: skeletons,
        map: skeletons,
      });
      setIsRefreshing(true);
      setError(null);

      try {
        // Fetch from API
        console.log('🔷 Azure Dashboard: Fetching from API');
        const dashboardData = await fetchAzureDashboardData(accountIds[0], forceRefresh);

        setData(dashboardData);
        loadedSelectionRef.current = key;
        setLastUpdated(dashboardData.lastUpdated || new Date().toISOString());
        saveToCache(dashboardData);

        setLoading({
          global: false,
          kpi: false,
          inventory: false,
          cost: false,
          optimization: false,
          map: false,
        });
      } catch (err: any) {
        console.error('🔷 Azure Dashboard: Error loading data', err);
        setError({
          section: 'global',
          message: err.message || 'Failed to load Azure dashboard data',
          timestamp: new Date().toISOString(),
        });
        // Whatever is on screen stays on screen. A failed revalidation should not cost the
        // user the figures they were already looking at; the error surfaces separately.
        setLoading({
          global: false,
          kpi: false,
          inventory: false,
          cost: false,
          optimization: false,
          map: false,
        });
      } finally {
        isFetchingRef.current = false;
        setIsRefreshing(false);
      }
    },
    [accountIds, selectionKey, loadFromCache, saveToCache]
  );

  // Refresh function
  const refresh = useCallback(async () => {
    console.log('🔷 Azure Dashboard: Manual refresh triggered');
    await loadDashboardData(true);
  }, [loadDashboardData]);

  // Clear error
  const clearError = useCallback(() => {
    setError(null);
  }, []);

  // Initial load and account change detection
  useEffect(() => {
    const accountIdsChanged = JSON.stringify(previousAccountIdsRef.current) !== JSON.stringify(accountIds);

    if (accountIdsChanged || !initializedRef.current) {
      console.log('🔷 Azure Dashboard: Account IDs changed or initial load', { accountIds, accountIdsChanged });
      previousAccountIdsRef.current = accountIds;
      initializedRef.current = true;

      // No clearing of the payload here any more.
      //
      // This used to call setData(null) whenever accountIdsChanged was true, and
      // previousAccountIdsRef starts empty — so it fired on every mount, including a remount from
      // navigating back to the page, not only on a real account switch. That was the blanking.
      //
      // loadedSelectionRef inside loadDashboardData now decides this properly, the way the AWS
      // hook does: a selection that does not match what is on screen raises skeletons across every
      // section, which covers an account switch without ever showing one subscription's figures
      // under another's name.

      // Paint from the session copy, then revalidate.
      loadDashboardData(false, true);
    }
  }, [accountIds, loadDashboardData]);

  // Auto-refresh
  useEffect(() => {
    if (!autoRefresh || accountIds.length === 0) {
      return;
    }

    console.log(`🔷 Azure Dashboard: Setting up auto-refresh (${refreshInterval}s)`);

    // Clear existing timer
    if (refreshTimerRef.current) {
      clearInterval(refreshTimerRef.current);
    }

    // The poll always goes to the server — never painted from cache, because there is already
    // something on screen and the whole point is to replace it with something newer.
    refreshTimerRef.current = setInterval(() => {
      console.log('🔷 Azure Dashboard: Auto-refresh triggered (server read)');
      loadDashboardData(false, false);
    }, refreshInterval * 1000);

    // Cleanup
    return () => {
      if (refreshTimerRef.current) {
        clearInterval(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
    };
  }, [autoRefresh, refreshInterval, accountIds, loadDashboardData]);

  return {
    data,
    loading,
    isRefreshing,
    error,
    refresh,
    clearError,
    lastUpdated,
  };
};
