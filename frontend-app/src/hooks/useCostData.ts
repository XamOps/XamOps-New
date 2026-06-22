/**
 * useCostData Hook
 *
 * Custom hook for fetching and managing cost management data
 * Handles cost breakdown, historical trends, and forecasting
 */

import { useState, useEffect, useCallback } from 'react';
import type { CostDataItem, CostFilters, HistoricalData, ForecastData, ForecastResponse, ForecastAccuracyMetrics } from '../types/cost';

interface UseCostDataParams {
  selectedAccountId: string | null;
  filters: CostFilters;
}

interface UseCostDataReturn {
  costData: CostDataItem[];
  isLoading: boolean;
  isRefreshing: boolean;
  loadCostData: (forceRefresh?: boolean) => Promise<void>;
  loadHistoricalData: (dimension: string, forceRefresh?: boolean) => Promise<HistoricalData | null>;
  loadForecast: (service: string, periods: number, forceRefresh?: boolean) => Promise<ForecastData | null>;
  loadForecastAccuracy: (service: string) => Promise<ForecastAccuracyMetrics | null>;
  refreshAll: () => Promise<void>;
}

export const useCostData = ({
  selectedAccountId,
  filters,
}: UseCostDataParams): UseCostDataReturn => {
  const [costData, setCostData] = useState<CostDataItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  /**
   * Load cost breakdown data
   */
  const loadCostData = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setIsLoading(false);
      setCostData([]);
      return;
    }

    if (forceRefresh) {
      setIsRefreshing(true);
    } else {
      setIsLoading(true);
    }

    const timestamp = new Date().getTime();
    let url = `/api/xamops/costs/breakdown?accountId=${selectedAccountId}&groupBy=${filters.groupBy}&forceRefresh=${forceRefresh}&startDate=${filters.startDate}&endDate=${filters.endDate}&_t=${timestamp}`;

    if (filters.groupBy === 'TAG' && filters.tagKey) {
      url += `&tag=${filters.tagKey}`;
    }

    try {
      const response = await fetch(url);

      if (response.status === 202) {
        console.log('Cost breakdown refresh initiated. Waiting for WebSocket.');
        return;
      }

      if (!response.ok) {
        console.error('Failed to load cost data, status:', response.status);
        setCostData([]);
        return;
      }

      const data = await response.json();

      if (Array.isArray(data)) {
        setCostData(data);
      } else {
        setCostData([]);
      }
    } catch (err) {
      console.error('Failed to load cost data:', err);
      setCostData([]);
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  }, [selectedAccountId, filters]);

  /**
   * Load historical trend data for a specific dimension
   */
  const loadHistoricalData = useCallback(async (
    dimension: string,
    forceRefresh = false
  ): Promise<HistoricalData | null> => {
    if (!selectedAccountId || !dimension) return null;

    try {
      let url = `/api/xamops/costs/historical-trend?accountId=${selectedAccountId}&days=30&forceRefresh=${forceRefresh}`;

      if (dimension) {
        url += `&serviceName=${encodeURIComponent(dimension)}`;
      }

      const response = await fetch(url);

      if (!response.ok) {
        throw new Error('Failed to load historical cost');
      }

      const data = await response.json();
      return data;
    } catch (error) {
      console.error('Error loading historical cost:', error);
      return null;
    }
  }, [selectedAccountId]);

  /**
   * Load cost forecast data
   */
  const loadForecast = useCallback(async (
    service: string,
    periods: number,
    forceRefresh = false
  ): Promise<ForecastData | null> => {
    if (!selectedAccountId) return null;

    try {
      let url = `/api/xamops/forecast/cost?accountId=${selectedAccountId}&periods=${periods}&forceRefresh=${forceRefresh}`;

      // Always send serviceName parameter - use "ALL" for empty/all services
      const serviceParam = service && service.trim() !== '' ? service : 'ALL';
      url += `&serviceName=${encodeURIComponent(serviceParam)}`;

      console.log('🔮 Fetching forecast from:', url);

      const response = await fetch(url);

      if (!response.ok) {
        throw new Error(`Failed to load forecast: ${response.status}`);
      }

      const result: ForecastResponse = await response.json();
      console.log('📊 Forecast response:', result);

      if (result.status === 'success' && result.forecast) {
        if (result.forecast.dates && result.forecast.dates.length > 0) {
          console.log(`✅ Forecast loaded: ${result.forecast.dates.length} periods`);
          return result.forecast;
        } else {
          console.warn('⚠️ Forecast data is empty or invalid');
          return null;
        }
      } else {
        console.warn('⚠️ Invalid forecast response');
        return null;
      }
    } catch (error) {
      console.error('❌ Error loading forecast:', error);
      return null;
    }
  }, [selectedAccountId]);

  /**
   * Load forecast accuracy metrics for a service (or all services)
   */
  const loadForecastAccuracy = useCallback(async (
    service: string
  ): Promise<ForecastAccuracyMetrics | null> => {
    if (!selectedAccountId) return null;

    try {
      const serviceParam = service && service.trim() !== '' ? service : 'ALL';
      const url = `/api/xamops/forecast/accuracy?accountId=${selectedAccountId}&serviceName=${encodeURIComponent(serviceParam)}&provider=AWS&days=90`;

      const response = await fetch(url);
      if (!response.ok) {
        return null;
      }
      const data: ForecastAccuracyMetrics = await response.json();
      return data;
    } catch (error) {
      console.error('Error loading forecast accuracy:', error);
      return null;
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
      console.log('[Cost] Loading cost data...');
      loadCostData();
    }
  }, [selectedAccountId, filters, loadCostData]);

  return {
    costData,
    isLoading,
    isRefreshing,
    loadCostData,
    loadHistoricalData,
    loadForecast,
    loadForecastAccuracy,
    refreshAll,
  };
};
