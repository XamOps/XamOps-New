/**
 * useAzureCostData Hook
 *
 * Custom hook for fetching and managing Azure cost management data.
 * Reuses the existing dashboard endpoint for the KPI and history figures, the dedicated
 * forecast and per-service trend endpoints, and the cost-insights endpoints for the
 * filterable breakdown, the billing currency and the forecast's track record.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { fetchAzureDashboardData } from '../api/azureDashboardApi';
import {
  fetchAzureBreakdown,
  fetchAzureCurrency,
  fetchAzureTagKeys,
  type AzureBreakdownDimension,
  type AzureCostAmount,
} from '../api/azureCostInsightsApi';
import type { AzureCostHistory, AzureBillingItem } from '../types/azureDashboard';
import type { ForecastAccuracyMetrics } from '../types/cost';

export interface AzureCostKPIs {
  mtdSpend: number;
  forecastedSpend: number;
  lastMonthSpend: number;
}

/**
 * What the breakdown section is asking for.
 *
 * <p>Azure had none of this: the breakdown read a fixed month-to-date figure out of the ingestion
 * cache, so "the first twelve days" and "grouped by resource group" were questions the page could not
 * be asked. The AWS page has had a date range and a group-by since it was written.
 */
export interface AzureCostFilters {
  /** Inclusive, `YYYY-MM-DD`. */
  startDate: string;
  /** Inclusive, `YYYY-MM-DD`. */
  endDate: string;
  groupBy: AzureBreakdownDimension;
  /** Which tag's values to group by. Only read when `groupBy` is `TAG`. */
  tagKey?: string;
}

export interface AzureForecastData {
  dates: string[];
  predictions: number[];
  upperBounds: number[];
  lowerBounds: number[];
  /**
   * Whether each point is a day or a month.
   *
   * <p>Worth carrying rather than assuming. The backend fits daily observations where it has them and
   * falls back to the six-month history where it does not, and Prophet extends a series at the
   * frequency of its input — so a fallback forecast of 30 periods is 30 months. The chart captioned
   * every forecast "next 30 days" and drew two and a half years on the fallback path.
   */
  granularity?: 'DAILY' | 'MONTHLY';
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
  /** How the previous forecasts turned out, or null while nothing has been scored yet. */
  forecastAccuracy: ForecastAccuracyMetrics | null;
  /** Which meter category the forecast covers. `'ALL'` for the whole subscription. */
  forecastService: string;
  /**
   * Meter categories with enough daily history to be forecast on their own.
   *
   * <p>Deliberately not every service the breakdown knows about: a service billed for three days
   * cannot be fitted, and listing it would put an option in the selector that answers with an empty
   * chart.
   */
  forecastableServices: string[];
  changeForecastService: (service: string) => Promise<void>;
  serviceTrend: AzureServiceTrend;
  loadServiceTrend: (serviceName: string) => Promise<void>;
  clearServiceTrend: () => void;
  /**
   * ISO code every figure here is denominated in.
   *
   * <p>Read from the backend rather than assumed. Every Azure cost component hardcoded `'₹'` and one
   * hardcoded `'$'` — both correct for an INR-billed subscription and both wrong for any other, which
   * on a cost page is the whole point of the number.
   */
  currency: string;
  /** Spend for the requested range and grouping, or null when the subscription cannot be asked. */
  breakdown: AzureCostAmount[] | null;
  isBreakdownLoading: boolean;
  /** Every tag key Cost Management knows about, for the chargeback picker. Empty is normal. */
  tagKeys: string[];
}

export const useAzureCostData = (
  selectedAccountId: string | null,
  filters?: AzureCostFilters
): UseAzureCostDataReturn => {
  const [billingSummary, setBillingSummary] = useState<AzureBillingItem[]>([]);
  const [costByRegion, setCostByRegion] = useState<Record<string, number>>({});
  const [costHistory, setCostHistory] = useState<AzureCostHistory>({ labels: [], costs: [], forecast: [] });
  const [kpis, setKpis] = useState<AzureCostKPIs>({ mtdSpend: 0, forecastedSpend: 0, lastMonthSpend: 0 });
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);

  const [forecast, setForecast] = useState<AzureForecastData | null>(null);
  const [isForecastLoading, setIsForecastLoading] = useState(false);
  const [forecastAccuracy, setForecastAccuracy] = useState<ForecastAccuracyMetrics | null>(null);
  const [forecastService, setForecastService] = useState('ALL');
  const [forecastableServices, setForecastableServices] = useState<string[]>([]);
  const [serviceTrend, setServiceTrend] = useState<AzureServiceTrend>({
    dimension: null,
    data: null,
    isLoading: false,
  });
  const [currency, setCurrency] = useState('USD');
  const [breakdown, setBreakdown] = useState<AzureCostAmount[] | null>(null);
  const [isBreakdownLoading, setIsBreakdownLoading] = useState(false);
  const [tagKeys, setTagKeys] = useState<string[]>([]);

  const isFetchingRef = useRef(false);
  const previousAccountRef = useRef<string | null>(null);
  const initializedRef = useRef(false);

  /**
   * How the previous forecasts for this subscription turned out.
   *
   * <p>Azure predictions were recorded nowhere and scored against nothing until now, so this could
   * only ever have reported "learning". Both halves are in place: the forecast endpoint records its
   * predictions, and the nightly evaluation scores them against the daily cost series that billing
   * ingestion already caches.
   *
   * <p>A failure here is swallowed — an absent badge is a smaller loss than a forecast that will not
   * render because its footnote could not be fetched.
   */
  const fetchAccuracy = useCallback(async (accountId: string, service = 'ALL') => {
    try {
      const params = new URLSearchParams({
        accountId,
        provider: 'AZURE',
        // Scoped to whatever the forecast on screen covers: a subscription-wide track record says
        // nothing about how well one service is predicted.
        serviceName: service || 'ALL',
        days: '90',
      });
      const resp = await fetch(`/api/xamops/forecast/accuracy?${params}`, {
        credentials: 'include',
      });
      if (!resp.ok) {
        setForecastAccuracy(null);
        return;
      }
      setForecastAccuracy((await resp.json()) as ForecastAccuracyMetrics);
    } catch {
      setForecastAccuracy(null);
    }
  }, []);

  const fetchForecast = useCallback(async (accountId: string, service = 'ALL') => {
    setIsForecastLoading(true);
    try {
      const params = new URLSearchParams({ accountId, periods: '30' });
      if (service && service !== 'ALL') params.set('serviceName', service);
      const resp = await fetch(`/api/xamops/azure/forecast/cost?${params}`, {
        credentials: 'include',
      });
      const json = await resp.json();
      if (json.status === 'success' && json.forecast) {
        setForecast({
          ...(json.forecast as AzureForecastData),
          granularity: json.granularity === 'MONTHLY' ? 'MONTHLY' : 'DAILY',
        });
      } else {
        setForecast(null);
      }
    } catch (err) {
      console.warn('[Azure Cost] Forecast fetch failed:', err);
      setForecast(null);
    } finally {
      setIsForecastLoading(false);
    }
    // After the forecast, not before: the recording happens inside that request, so asking for the
    // track record first would miss the batch this page view just created.
    void fetchAccuracy(accountId, service);
  }, [fetchAccuracy]);

  /** Switches the forecast to one meter category, or back to the whole subscription. */
  const changeForecastService = useCallback(
    async (service: string) => {
      setForecastService(service);
      if (selectedAccountId) {
        await fetchForecast(selectedAccountId, service);
      }
    },
    [selectedAccountId, fetchForecast],
  );

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
      setCostHistory(data.costHistory || { labels: [], costs: [], forecast: [] });
      setKpis({
        mtdSpend: data.kpi?.mtdSpend || 0,
        forecastedSpend: data.kpi?.forecastedSpend || 0,
        lastMonthSpend: data.kpi?.lastMonthSpend || 0,
      });
      // The dashboard payload carries the currency when ingestion has recorded one. Taken from here
      // rather than from a second request because it arrives with the figures it describes, so the
      // cards cannot paint a symbol that disagrees with the number beside it.
      if (data.currency) {
        setCurrency(data.currency);
      }

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
        setForecastAccuracy(null);
        // Back to the whole subscription: a service selected on the previous account may not exist on
        // this one, and a forecast request for it would answer empty under a stale label.
        setForecastService('ALL');
        setForecastableServices([]);
        setServiceTrend({ dimension: null, data: null, isLoading: false });
        loadData();
      }
    } else {
      setBillingSummary([]);
      setCostByRegion({});
      setCostHistory({ labels: [], costs: [], forecast: [] });
      setKpis({ mtdSpend: 0, forecastedSpend: 0, lastMonthSpend: 0 });
      setForecast(null);
      setForecastAccuracy(null);
      setForecastService('ALL');
      setForecastableServices([]);
      setServiceTrend({ dimension: null, data: null, isLoading: false });
      setBreakdown(null);
      setTagKeys([]);
      setCurrency('USD');
      setIsLoading(false);
    }
  }, [selectedAccountId, loadData]);

  /** Which services can be forecast individually. Loaded once per subscription. */
  useEffect(() => {
    if (!selectedAccountId) return;
    let cancelled = false;
    fetch(`/api/xamops/azure/forecast/services?accountId=${encodeURIComponent(selectedAccountId)}`, {
      credentials: 'include',
    })
      .then((resp) => (resp.ok ? resp.json() : []))
      .then((services) => {
        if (!cancelled && Array.isArray(services)) setForecastableServices(services);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [selectedAccountId]);

  /**
   * The currency, from its own endpoint, as a backstop.
   *
   * <p>The dashboard payload usually carries it, but not on a subscription whose snapshot predates the
   * field. Asked for separately so the cards are not left rendering dollars on a rupee-billed
   * subscription just because its snapshot is old — the cost-insights endpoint reads the ingestion
   * cache directly and answers even when the dashboard snapshot does not.
   */
  useEffect(() => {
    if (!selectedAccountId) return;
    let cancelled = false;
    fetchAzureCurrency(selectedAccountId)
      .then((code) => {
        // Only fills a gap; never overrides a currency that arrived beside actual figures.
        if (!cancelled && code) {
          setCurrency((current) => (current === 'USD' ? code : current));
        }
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [selectedAccountId]);

  /** Tag keys for the chargeback picker. Loaded once per subscription; an empty list is normal. */
  useEffect(() => {
    if (!selectedAccountId) return;
    let cancelled = false;
    fetchAzureTagKeys(selectedAccountId)
      .then((keys) => {
        if (!cancelled) setTagKeys(keys);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [selectedAccountId]);

  // Destructured so the effect below depends on the values rather than on the object identity: the
  // page rebuilds the filters object on every render, and depending on it would refetch in a loop.
  const startDate = filters?.startDate;
  const endDate = filters?.endDate;
  const groupBy = filters?.groupBy;
  const tagKey = filters?.tagKey;

  /**
   * Spend for the requested range and grouping.
   *
   * <p>Separate from {@link loadData} on purpose. That reads a snapshot and is what the KPI cards and
   * the history chart want; this one queries Cost Management live for whatever range the reader asked
   * for, so a filter change must not also re-trigger a dashboard refresh.
   *
   * <p>A stale response is discarded rather than applied: changing the group-by twice quickly would
   * otherwise let the first answer land last and show region figures under a "Resource group"
   * heading.
   */
  useEffect(() => {
    if (!selectedAccountId || !startDate || !endDate || !groupBy) {
      setBreakdown(null);
      return;
    }
    if (groupBy === 'TAG' && !tagKey) {
      // Nothing to group by yet. Null rather than empty, so the section says "pick a tag" instead of
      // "this subscription has no spend".
      setBreakdown(null);
      return;
    }

    let cancelled = false;
    setIsBreakdownLoading(true);
    fetchAzureBreakdown(selectedAccountId, startDate, endDate, groupBy, tagKey)
      .then((result) => {
        if (cancelled) return;
        setBreakdown(result ? result.costs : null);
        if (result?.currency) {
          setCurrency(result.currency);
        }
      })
      .catch(() => {
        if (!cancelled) setBreakdown(null);
      })
      .finally(() => {
        if (!cancelled) setIsBreakdownLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedAccountId, startDate, endDate, groupBy, tagKey]);

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
    forecastAccuracy,
    forecastService,
    forecastableServices,
    changeForecastService,
    serviceTrend,
    loadServiceTrend,
    clearServiceTrend,
    currency,
    breakdown,
    isBreakdownLoading,
    tagKeys,
  };
};
