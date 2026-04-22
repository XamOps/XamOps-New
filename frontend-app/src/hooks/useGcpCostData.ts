/**
 * useGcpCostData Hook
 *
 * Custom hook for fetching and managing GCP cost management data
 * Handles cost breakdown and forecasting for GCP accounts
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type { GcpCostDataItem, GcpCostFilters, GcpForecastDataPoint } from '../types/gcpCost';

interface UseGcpCostDataParams {
  selectedAccountId: string | null;
  filters: GcpCostFilters;
}

interface UseGcpCostDataReturn {
  costData: GcpCostDataItem[];
  isLoading: boolean;
  isRefreshing: boolean;
  loadCostData: (forceRefresh?: boolean) => Promise<void>;
  loadForecastServices: () => Promise<string[]>;
  loadForecast: (service: string, periods: number) => Promise<GcpForecastDataPoint[]>;
  refreshAll: () => Promise<void>;
}

export const useGcpCostData = ({
  selectedAccountId,
  filters,
}: UseGcpCostDataParams): UseGcpCostDataReturn => {
  const [costData, setCostData] = useState<GcpCostDataItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  // Refs for stable execution
  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

  /**
   * Load cost breakdown data from GCP API
   */
  const loadCostData = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setIsLoading(false);
      setCostData([]);
      return;
    }

    if (isFetchingRef.current && !forceRefresh) {
      console.log('[GCP Cost] Fetch already in progress, skipping...');
      return;
    }

    isFetchingRef.current = true;

    if (forceRefresh) {
      setIsRefreshing(true);
    } else {
      setIsLoading(true);
    }

    try {
      let url = `/api/xamops/gcp/costs/breakdown?accountId=${selectedAccountId}&groupBy=${filters.groupBy}&forceRefresh=${forceRefresh}`;

      if (filters.groupBy === 'TAG' && filters.tagKey) {
        url = `/api/xamops/gcp/costs/breakdown-by-tag?accountId=${selectedAccountId}&tagKey=${filters.tagKey}&forceRefresh=${forceRefresh}`;
      }

      console.log(`[GCP Cost] Fetching cost data for account: ${selectedAccountId}, groupBy: ${filters.groupBy}`);
      const response = await fetch(url);

      if (!response.ok) {
        console.error('Failed to load GCP cost data, status:', response.status);
        setCostData([]);
        return;
      }

      const data = await response.json();
      console.log('[GCP Cost] Cost data received:', Array.isArray(data) ? data.length : 0, 'items');

      if (Array.isArray(data)) {
        setCostData(data);
      } else {
        setCostData([]);
      }
    } catch (err) {
      console.error('Failed to load GCP cost data:', err);
      setCostData([]);
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
      isFetchingRef.current = false;
    }
  }, [selectedAccountId, filters]);

  /**
   * Load available services for forecast dropdown
   */
  const loadForecastServices = useCallback(async (): Promise<string[]> => {
    if (!selectedAccountId) return [];

    try {
      const response = await fetch(
        `/api/xamops/gcp/costs/breakdown?accountId=${selectedAccountId}&groupBy=SERVICE`
      );

      if (!response.ok) {
        return [];
      }

      const data = await response.json();
      const services = data.map((item: GcpCostDataItem) => item.name).filter(Boolean);
      return ['ALL', ...new Set<string>(services)];
    } catch (err) {
      console.error('Failed to load forecast services:', err);
      return [];
    }
  }, [selectedAccountId]);

  /**
   * Load cost forecast data
   */
  const loadForecast = useCallback(async (
    service: string,
    periods: number
  ): Promise<GcpForecastDataPoint[]> => {
    if (!selectedAccountId) return [];

    try {
      const url = `/api/xamops/gcp/forecast/cost?accountId=${selectedAccountId}&periods=${periods}&serviceName=${encodeURIComponent(service)}`;

      const response = await fetch(url);

      if (!response.ok) {
        throw new Error(`Failed to load forecast: ${response.status}`);
      }

      const forecastData = await response.json();
      return forecastData || [];
    } catch (err) {
      console.error('Failed to load GCP forecast:', err);
      return [];
    }
  }, [selectedAccountId]);

  /**
   * Refresh all data
   */
  const refreshAll = useCallback(async () => {
    await loadCostData(true);
  }, [loadCostData]);

  // Load data when account or filters change
  useEffect(() => {
    if (selectedAccountId) {
      const isDifferentAccount = previousAccountRef.current !== selectedAccountId;

      if (isDifferentAccount || !initializedRef.current) {
        console.log('[GCP Cost] Account changed or Initial load, loading cost data...');
        previousAccountRef.current = selectedAccountId;
        initializedRef.current = true;
        loadCostData();
      }
    } else {
      setCostData([]);
      setIsLoading(false);
    }
  }, [selectedAccountId, filters, loadCostData]);

  return {
    costData,
    isLoading,
    isRefreshing,
    loadCostData,
    loadForecastServices,
    loadForecast,
    refreshAll,
  };
};
