import { useState, useCallback, useEffect, useRef } from 'react';
import type { AzureReservationData } from '../types/azureReservations';

const EMPTY_DATA: AzureReservationData = {
  reservations: [],
  savingsPlans: [],
  metrics: {
    totalReservations: 0,
    activeReservations: 0,
    totalSavingsPlans: 0,
    activeSavingsPlans: 0,
  },
  recommendations: [],
  totalMonthlySavings: 0,
  indicativeMonthlySavings: 0,
  indicativeRecommendationCount: 0,
  recommendationsUnavailableReason: null,
  recommendationCurrency: null,
  mixedCurrencies: false,
  savingsUnavailableReason: null,
  inventoryUnavailableReason: null,
};

interface UseAzureReservationsDataReturn {
  data: AzureReservationData;
  isLoading: boolean;
  error: Error | null;
  refresh: () => Promise<void>;
}

export const useAzureReservationsData = (
  selectedAccountId: string | null
): UseAzureReservationsDataReturn => {
  const [data, setData] = useState<AzureReservationData>(EMPTY_DATA);
  // Starts true when a subscription is selected, so the first frame is the skeleton rather than
  // a flash of "No Reservation Data Available".
  const [isLoading, setIsLoading] = useState(Boolean(selectedAccountId));
  const [error, setError] = useState<Error | null>(null);
  // Each request gets a number; only the latest one may write state. Without this a slow response
  // for the previous subscription could land after a switch and show its reservations under the
  // new one.
  const requestSeq = useRef(0);

  const fetchData = useCallback(
    async (forceRefresh = false) => {
      const requestId = ++requestSeq.current;
      if (!selectedAccountId) {
        setData(EMPTY_DATA);
        setIsLoading(false);
        return;
      }

      setIsLoading(true);
      setError(null);

      try {
        const url = `/api/xamops/azure/reservations?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;
        console.log('[AzureReservations] Fetching from:', url);

        const response = await fetch(url, {
          credentials: 'include',
          headers: { Accept: 'application/json' },
        });

        if (!response.ok) {
          throw new Error(`HTTP error! status: ${response.status}`);
        }

        const json: AzureReservationData = await response.json();
        if (requestId !== requestSeq.current) return;
        setData({
          reservations: json.reservations ?? [],
          savingsPlans: json.savingsPlans ?? [],
          metrics: json.metrics ?? EMPTY_DATA.metrics,
          recommendations: json.recommendations ?? [],
          totalMonthlySavings: json.totalMonthlySavings ?? 0,
          indicativeMonthlySavings: json.indicativeMonthlySavings ?? 0,
          indicativeRecommendationCount: json.indicativeRecommendationCount ?? 0,
          // Null and undefined both mean "Azure answered normally"; anything else is a reason the
          // empty state has to show instead of guessing at why the tab is blank.
          recommendationsUnavailableReason: json.recommendationsUnavailableReason ?? null,
          recommendationCurrency: json.recommendationCurrency ?? null,
          mixedCurrencies: json.mixedCurrencies ?? false,
          savingsUnavailableReason: json.savingsUnavailableReason ?? null,
          inventoryUnavailableReason: json.inventoryUnavailableReason ?? null,
        });
      } catch (err) {
        if (requestId !== requestSeq.current) return;
        console.error('[AzureReservations] Failed to load data:', err);
        setError(err instanceof Error ? err : new Error('Failed to load reservation data'));
        setData(EMPTY_DATA);
      } finally {
        if (requestId === requestSeq.current) setIsLoading(false);
      }
    },
    [selectedAccountId]
  );

  useEffect(() => {
    // A new subscription starts from nothing rather than showing the previous one's data.
    setData(EMPTY_DATA);
    fetchData(false);
  }, [fetchData]);

  return {
    data,
    isLoading,
    error,
    refresh: () => fetchData(true),
  };
};
