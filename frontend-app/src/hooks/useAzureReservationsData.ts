import { useState, useCallback, useEffect } from 'react';
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
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const fetchData = useCallback(
    async (forceRefresh = false) => {
      if (!selectedAccountId) {
        setData(EMPTY_DATA);
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
        setData({
          reservations: json.reservations ?? [],
          savingsPlans: json.savingsPlans ?? [],
          metrics: json.metrics ?? EMPTY_DATA.metrics,
          recommendations: json.recommendations ?? [],
        });
      } catch (err) {
        console.error('[AzureReservations] Failed to load data:', err);
        setError(err instanceof Error ? err : new Error('Failed to load reservation data'));
        setData(EMPTY_DATA);
      } finally {
        setIsLoading(false);
      }
    },
    [selectedAccountId]
  );

  useEffect(() => {
    fetchData(false);
  }, [fetchData]);

  return {
    data,
    isLoading,
    error,
    refresh: () => fetchData(true),
  };
};
