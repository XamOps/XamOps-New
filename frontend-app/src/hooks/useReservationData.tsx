/**
 * Custom hook for Reservation Management data fetching
 *
 * Converted from: reservation.html (Alpine.js reservationManagement function)
 *
 * Features:
 * - Fetches reservation inventory, savings plans, and analysis
 * - Fetches purchase recommendations with filters
 * - Session storage caching
 * - Force refresh capability
 * - Loading and error states
 */

import { useState, useCallback, useEffect } from 'react';
import type {
  ReservationData,
  PurchaseRecommendation,
  RecommendationFilters,
} from '../types/reservation';

interface UseReservationDataProps {
  selectedAccountId: string | null;
}

interface UseReservationDataReturn {
  data: ReservationData;
  isLoading: boolean;
  isRecsLoading: boolean;
  error: Error | null;
  recFilters: RecommendationFilters;
  setRecFilters: React.Dispatch<React.SetStateAction<RecommendationFilters>>;
  fetchReservationData: (forceRefresh: boolean) => Promise<void>;
  fetchPurchaseRecommendations: (forceRefresh: boolean) => Promise<void>;
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
  });

  const [isLoading, setIsLoading] = useState(true);
  const [isRecsLoading, setIsRecsLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const [recFilters, setRecFilters] = useState<RecommendationFilters>({
    term: 'ONE_YEAR',
    paymentOption: 'NO_UPFRONT',
    lookback: 'THIRTY_DAYS',
    offeringClass: 'STANDARD',
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

      const cacheKey = `reservationData-${selectedAccountId}`;
      const cachedData = sessionStorage.getItem(cacheKey);

      // Return cached data if available and not forcing refresh
      if (cachedData && !forceRefresh) {
        const parsed = JSON.parse(cachedData);
        if (!parsed.savingsPlans) parsed.savingsPlans = [];
        setData(parsed);
        setIsLoading(false);
        return;
      }

      setIsLoading(true);
      setError(null);

      try {
        const url = `/api/xamops/reservations?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;
        console.log('[Reservation] Fetching data from:', url);

        const response = await fetch(url, {
          credentials: 'include',
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
        setData(responseData);
        sessionStorage.setItem(cacheKey, JSON.stringify(responseData));
      } catch (err) {
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
  const fetchPurchaseRecommendations = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) return;

      setIsRecsLoading(true);

      try {
        const { term, paymentOption, lookback, offeringClass } = recFilters;
        const url = `/api/xamops/reservations/recommendations?accountId=${selectedAccountId}&term=${term}&paymentOption=${paymentOption}&lookback=${lookback}&offeringClass=${offeringClass}&forceRefresh=${forceRefresh}`;
        console.log('[Reservation] Fetching recommendations from:', url);

        const response = await fetch(url, {
          credentials: 'include',
          headers: {
            Accept: 'application/json',
          },
        });

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const recommendations: PurchaseRecommendation[] = await response.json();
        console.log('[Reservation] Recommendations fetched:', recommendations.length);

        setData((prev) => ({
          ...prev,
          purchaseRecommendations: recommendations,
        }));
      } catch (err) {
        console.error('[Reservation] Failed to load recommendations:', err);
        setData((prev) => ({
          ...prev,
          purchaseRecommendations: [],
        }));
      } finally {
        setIsRecsLoading(false);
      }
    },
    [selectedAccountId, recFilters]
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
    error,
    recFilters,
    setRecFilters,
    fetchReservationData,
    fetchPurchaseRecommendations,
  };
};
