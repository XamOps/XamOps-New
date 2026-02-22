/**
 * useAzureCostData Hook
 *
 * Custom hook for fetching and managing Azure cost management data.
 * Reuses the existing dashboard endpoint for main data, plus dedicated
 * forecast and per-service trend endpoints.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { fetchAzureDashboardData } from '../api/azureDashboardApi';
import type { AzureCostHistory, AzureBillingItem } from '../types/azureDashboard';

export interface AzureCostKPIs {
  mtdSpend: number;
  forecastedSpend: number;
  lastMonthSpend: number;
}

export interface AzureForecastData {
  dates: string[];
  predictions: number[];
  upperBounds: number[];
  lowerBounds: number[];
}

export interface AzureServiceTrend {
  dimension: string | null;
  data: { labels: string[]; costs: number[] } | null;
  isLoading: boolean;
}

export interface UseAzureCostDataReturn {
  billingSummary: AzureBillingItem[];
  costByRegion: Record<string, number>;
  costHistory: AzureCostHistory;
  kpis: AzureCostKPIs;
  isLoading: boolean;
  isRefreshing: boolean;
  refresh: () => Promise<void>;
  forecast: AzureForecastData | null;
  isForecastLoading: boolean;
  serviceTrend: AzureServiceTrend;
  loadServiceTrend: (serviceName: string) => Promise<void>;
  clearServiceTrend: () => void;
}

export const useAzureCostData = (
  selectedAccountId: string | null
): UseAzureCostDataReturn => {
  const [billingSummary, setBillingSummary] = useState<AzureBillingItem[]>([]);
  const [costByRegion, setCostByRegion] = useState<Record<string, number>>({});
  const [costHistory, setCostHistory] = useState<AzureCostHistory>({ labels: [], costs: [], anomalies: [] });
  const [kpis, setKpis] = useState<AzureCostKPIs>({ mtdSpend: 0, forecastedSpend: 0, lastMonthSpend: 0 });
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const [forecast, setForecast] = useState<AzureForecastData | null>(null);
  const [isForecastLoading, setIsForecastLoading] = useState(false);
  const [serviceTrend, setServiceTrend] = useState<AzureServiceTrend>({
    dimension: null,
    data: null,
    isLoading: false,
  });

  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

  const fetchForecast = useCallback(async (accountId: string) => {
    setIsForecastLoading(true);
    try {
      const resp = await fetch(
        `/api/xamops/azure/forecast/cost?accountId=${accountId}&periods=30`,
        { credentials: 'include' }
      );
      const json = await resp.json();
      if (json.status === 'success' && json.forecast) {
        setForecast(json.forecast as AzureForecastData);
      } else {
        setForecast(null);
      }
    } catch (err) {
      console.warn('[Azure Cost] Forecast fetch failed:', err);
      setForecast(null);
    } finally {
      setIsForecastLoading(false);
    }
  }, []);

  const loadData = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setIsLoading(false);
      return;
    }

    if (isFetchingRef.current && !forceRefresh) {
      return;
    }

    isFetchingRef.current = true;

    if (forceRefresh) {
      setIsRefreshing(true);
      // Trigger backend refresh via POST endpoint
      try {
        await fetch(`/api/xamops/azure/dashboard/refresh?accountId=${selectedAccountId}`, {
          method: 'POST',
          credentials: 'include',
        });
      } catch (err) {
        console.warn('[Azure Cost] Refresh trigger failed:', err);
      }
    } else {
      setIsLoading(true);
    }

    try {
      console.log(`[Azure Cost] Fetching data for account: ${selectedAccountId}`);
      const data = await fetchAzureDashboardData(selectedAccountId, false);

      setBillingSummary(data.billingSummary || []);
      setCostByRegion(data.costByRegion || {});
      setCostHistory(data.costHistory || { labels: [], costs: [], anomalies: [] });
      setKpis({
        mtdSpend: data.kpi?.mtdSpend || 0,
        forecastedSpend: data.kpi?.forecastedSpend || 0,
        lastMonthSpend: data.kpi?.lastMonthSpend || 0,
      });

      console.log('[Azure Cost] Data loaded:', {
        services: data.billingSummary?.length,
        regions: Object.keys(data.costByRegion || {}).length,
        historyMonths: data.costHistory?.labels?.length,
      });

      // Fetch forecast after main data is loaded
      fetchForecast(selectedAccountId);
    } catch (err) {
      console.error('[Azure Cost] Failed to load data:', err);
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
      isFetchingRef.current = false;
    }
  }, [selectedAccountId, fetchForecast]);

  const refresh = useCallback(async () => {
    await loadData(true);
  }, [loadData]);

  const loadServiceTrend = useCallback(async (serviceName: string) => {
    if (!selectedAccountId) return;
    setServiceTrend({ dimension: serviceName, data: null, isLoading: true });
    try {
      const resp = await fetch(
        `/api/xamops/azure/costs/service-trend?accountId=${selectedAccountId}&serviceName=${encodeURIComponent(serviceName)}`,
        { credentials: 'include' }
      );
      const data = await resp.json();
      setServiceTrend({ dimension: serviceName, data, isLoading: false });
    } catch (err) {
      console.error('[Azure Cost] Service trend fetch failed:', err);
      setServiceTrend({ dimension: serviceName, data: null, isLoading: false });
    }
  }, [selectedAccountId]);

  const clearServiceTrend = useCallback(() => {
    setServiceTrend({ dimension: null, data: null, isLoading: false });
  }, []);

  useEffect(() => {
    if (selectedAccountId) {
      const isDifferentAccount = previousAccountRef.current !== selectedAccountId;

      if (isDifferentAccount || !initializedRef.current) {
        console.log('[Azure Cost] Account changed or initial load, fetching...');
        previousAccountRef.current = selectedAccountId;
        initializedRef.current = true;
        setForecast(null);
        setServiceTrend({ dimension: null, data: null, isLoading: false });
        loadData();
      }
    } else {
      setBillingSummary([]);
      setCostByRegion({});
      setCostHistory({ labels: [], costs: [], anomalies: [] });
      setKpis({ mtdSpend: 0, forecastedSpend: 0, lastMonthSpend: 0 });
      setForecast(null);
      setServiceTrend({ dimension: null, data: null, isLoading: false });
      setIsLoading(false);
    }
  }, [selectedAccountId, loadData]);

  return {
    billingSummary,
    costByRegion,
    costHistory,
    kpis,
    isLoading,
    isRefreshing,
    refresh,
    forecast,
    isForecastLoading,
    serviceTrend,
    loadServiceTrend,
    clearServiceTrend,
  };
};
