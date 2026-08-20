/**
 * Custom hook for Reservation Management data fetching
 *
 * Converted from: reservation.html (Alpine.js reservationManagement function)
 *
 * Features:
 * - Fetches reservation inventory, savings plans, and analysis
 * - Fetches purchase recommendations with filters
 * - Session storage caching with expiration
 * - Force refresh capability
 * - Loading and error states
 * - Cache versioning and invalidation
 */

import { useState, useCallback, useEffect, useRef } from 'react';
import type {
  ReservationData,
  PurchaseRecommendation,
  RecommendationFilters,
  SavingsPlanRecommendation,
  SpRecommendationFilters,
} from '../types/reservation';

// Cache configuration
const CACHE_VERSION = 'v1';
const CACHE_DURATION = 5 * 60 * 1000; // 5 minutes in milliseconds

interface CachedData<T> {
  data: T;
  timestamp: number;
  version: string;
}

/**
 * Get data from cache if valid
 */
const getCachedData = <T,>(key: string): T | null => {
  try {
    const cached = sessionStorage.getItem(key);
    if (!cached) return null;

    const { data, timestamp, version }: CachedData<T> = JSON.parse(cached);

    // Check version
    if (version !== CACHE_VERSION) {
      sessionStorage.removeItem(key);
      return null;
    }

    // Check expiration
    const now = Date.now();
    if (now - timestamp > CACHE_DURATION) {
      sessionStorage.removeItem(key);
      return null;
    }

    return data;
  } catch (err) {
    console.error('[Cache] Failed to read cache:', err);
    return null;
  }
};

/**
 * Set data in cache with timestamp and version
 */
const setCachedData = <T,>(key: string, data: T): void => {
  try {
    const cached: CachedData<T> = {
      data,
      timestamp: Date.now(),
      version: CACHE_VERSION,
    };
    sessionStorage.setItem(key, JSON.stringify(cached));
  } catch (err) {
    console.error('[Cache] Failed to write cache:', err);
  }
};

/**
 * Clear all reservation caches
 */
const clearReservationCache = (): void => {
  try {
    const keys = Object.keys(sessionStorage);
    keys.forEach((key) => {
      if (key.startsWith('reservationData-') || key.startsWith('reservationRecs-') || key.startsWith('spRecs-')) {
        sessionStorage.removeItem(key);
      }
    });
    console.log('[Cache] Cleared all reservation caches');
  } catch (err) {
    console.error('[Cache] Failed to clear cache:', err);
  }
};

interface UseReservationDataProps {
  selectedAccountId: string | null;
}

interface UseReservationDataReturn {
  data: ReservationData;
  isLoading: boolean;
  isRecsLoading: boolean;
  /** True when the recommendation set is missing at least one service. */
  recsIncomplete: boolean;
  isSpRecsLoading: boolean;
  error: Error | null;
  recFilters: RecommendationFilters;
  setRecFilters: React.Dispatch<React.SetStateAction<RecommendationFilters>>;
  spRecFilters: SpRecommendationFilters;
  setSpRecFilters: React.Dispatch<React.SetStateAction<SpRecommendationFilters>>;
  fetchReservationData: (forceRefresh: boolean) => Promise<void>;
  fetchPurchaseRecommendations: (forceRefresh: boolean) => Promise<void>;
  fetchSpRecommendations: (forceRefresh: boolean) => Promise<void>;
}

export const useReservationData = ({
  selectedAccountId,
}: UseReservationDataProps): UseReservationDataReturn => {
  // State
  const [data, setData] = useState<ReservationData>({
    analysis: { utilizationPercentage: 0, coveragePercentage: 0 },
    historicalData: { labels: [], utilizationPercentages: [], coveragePercentages: [] },
    inventory: [],
    savingsPlans: [],
    purchaseRecommendations: [],
    savingsPlanRecommendations: [],
  });

  const [isLoading, setIsLoading] = useState(true);
  const [isRecsLoading, setIsRecsLoading] = useState(false);
  /**
   * True when at least one reservable service could not be queried, so an empty result must
   * not be presented as "fully optimized".
   */
  const [recsIncomplete, setRecsIncomplete] = useState(false);
  const [isSpRecsLoading, setIsSpRecsLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  const [recFilters, setRecFilters] = useState<RecommendationFilters>({
    term: 'ONE_YEAR',
    paymentOption: 'NO_UPFRONT',
    lookback: 'THIRTY_DAYS',
    offeringClass: 'STANDARD',
  });

  const [spRecFilters, setSpRecFilters] = useState<SpRecommendationFilters>({
    spType: 'COMPUTE_SP',
    term: 'ONE_YEAR',
    paymentOption: 'NO_UPFRONT',
    lookback: 'THIRTY_DAYS',
  });

  /**
   * Fetch reservation data from backend
   */
  const fetchReservationData = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setIsLoading(false);
        return;
      }

      // Cancel any in-flight request before starting a new one
      abortControllerRef.current?.abort();
      abortControllerRef.current = new AbortController();
      const { signal } = abortControllerRef.current;

      const cacheKey = `reservationData-${selectedAccountId}`;

      // Return cached data if available and not forcing refresh
      if (!forceRefresh) {
        const cachedData = getCachedData<ReservationData>(cacheKey);
        if (cachedData) {
          console.log('[Reservation] Using cached data');
          if (!cachedData.savingsPlans) cachedData.savingsPlans = [];
          setData(cachedData);
          setIsLoading(false);
          return;
        }
      } else {
        // Clear cache on force refresh
        clearReservationCache();
      }

      setIsLoading(true);
      setError(null);

      try {
        const url = `/api/xamops/reservations?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;
        console.log('[Reservation] Fetching data from:', url);

        const response = await fetch(url, {
          credentials: 'include',
          signal,
          headers: {
            Accept: 'application/json',
          },
        });

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const responseData = await response.json();
        console.log('[Reservation] Data fetched successfully:', responseData);

        if (!responseData.savingsPlans) responseData.savingsPlans = [];
        if (!responseData.savingsPlanRecommendations) responseData.savingsPlanRecommendations = [];
        setData(responseData);
        setCachedData(cacheKey, responseData);
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        console.error('[Reservation] Failed to load data:', err);
        setError(err instanceof Error ? err : new Error('Failed to load reservation data'));
      } finally {
        setIsLoading(false);
      }
    },
    [selectedAccountId]
  );

  /**
   * Fetch purchase recommendations from backend
   */
  const recsAbortControllerRef = useRef<AbortController | null>(null);

  const fetchPurchaseRecommendations = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) return;

      // Cancel any in-flight recommendations request
      recsAbortControllerRef.current?.abort();
      recsAbortControllerRef.current = new AbortController();
      const { signal } = recsAbortControllerRef.current;

      const { term, paymentOption, lookback, offeringClass } = recFilters;
      const cacheKey = `reservationRecs-${selectedAccountId}-${term}-${paymentOption}-${lookback}-${offeringClass}`;

      // Return cached recommendations if available and not forcing refresh
      if (!forceRefresh) {
        const cachedRecs = getCachedData<PurchaseRecommendation[]>(cacheKey);
        if (cachedRecs) {
          console.log('[Reservation] Using cached recommendations');
          setData((prev) => ({
            ...prev,
            purchaseRecommendations: cachedRecs,
          }));
          return;
        }
      }

      setIsRecsLoading(true);
      // Clear the previous result set. Without this the table kept showing rows fetched under
      // the old filters while the new request was in flight, so a 1-year row could appear
      // under a 3-year selection.
      setData((prev) => ({ ...prev, purchaseRecommendations: [] }));
      setRecsIncomplete(false);

      try {
        const url = `/api/xamops/reservations/recommendations?accountId=${selectedAccountId}&term=${term}&paymentOption=${paymentOption}&lookback=${lookback}&offeringClass=${offeringClass}&forceRefresh=${forceRefresh}`;
        console.log('[Reservation] Fetching recommendations from:', url);

        const response = await fetch(url, {
          credentials: 'include',
          signal,
          headers: {
            Accept: 'application/json',
          },
        });

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        // The backend reports whether every reservable service answered. An empty list with
        // complete=false means the lookup failed, not that the account is fully optimized.
        const complete = response.headers.get('X-Recommendations-Complete') !== 'false';
        const failedServices = response.headers.get('X-Recommendations-Failed-Services') || '';

        const recommendations: PurchaseRecommendation[] = await response.json();
        console.log('[Reservation] Recommendations fetched:', recommendations.length,
          complete ? '(complete)' : `(INCOMPLETE — failed: ${failedServices})`);

        setData((prev) => ({
          ...prev,
          purchaseRecommendations: recommendations,
        }));
        setRecsIncomplete(!complete);
        // Only cache a result we can stand behind, so a failed lookup does not stick.
        if (complete) {
          setCachedData(cacheKey, recommendations);
        }
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        console.error('[Reservation] Failed to load recommendations:', err);
        setData((prev) => ({
          ...prev,
          purchaseRecommendations: [],
        }));
        setRecsIncomplete(true);
      } finally {
        setIsRecsLoading(false);
      }
    },
    [selectedAccountId, recFilters]
  );

  const spRecsAbortControllerRef = useRef<AbortController | null>(null);

  const fetchSpRecommendations = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) return;

      spRecsAbortControllerRef.current?.abort();
      spRecsAbortControllerRef.current = new AbortController();
      const { signal } = spRecsAbortControllerRef.current;

      const { spType, term, paymentOption, lookback } = spRecFilters;
      const cacheKey = `spRecs-${selectedAccountId}-${spType}-${term}-${paymentOption}-${lookback}`;

      if (!forceRefresh) {
        const cached = getCachedData<SavingsPlanRecommendation[]>(cacheKey);
        if (cached) {
          setData((prev) => ({ ...prev, savingsPlanRecommendations: cached }));
          return;
        }
      }

      setIsSpRecsLoading(true);
      try {
        const url = `/api/xamops/reservations/sp-recommendations?accountId=${selectedAccountId}&spType=${spType}&term=${term}&paymentOption=${paymentOption}&lookback=${lookback}&forceRefresh=${forceRefresh}`;
        const response = await fetch(url, { credentials: 'include', signal, headers: { Accept: 'application/json' } });
        if (!response.ok) throw new Error(`HTTP error! status: ${response.status}`);
        const recs: SavingsPlanRecommendation[] = await response.json();
        setData((prev) => ({ ...prev, savingsPlanRecommendations: recs }));
        setCachedData(cacheKey, recs);
      } catch (err) {
        if (err instanceof DOMException && err.name === 'AbortError') return;
        setData((prev) => ({ ...prev, savingsPlanRecommendations: [] }));
      } finally {
        setIsSpRecsLoading(false);
      }
    },
    [selectedAccountId, spRecFilters]
  );

  // Initial data fetch when account changes
  useEffect(() => {
    if (selectedAccountId) {
      fetchReservationData(false);
    }
  }, [selectedAccountId, fetchReservationData]);

  return {
    data,
    isLoading,
    isRecsLoading,
    recsIncomplete,
    isSpRecsLoading,
    error,
    recFilters,
    setRecFilters,
    spRecFilters,
    setSpRecFilters,
    fetchReservationData,
    fetchPurchaseRecommendations,
    fetchSpRecommendations,
  };
};
