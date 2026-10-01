/**
 * useGcpCostData Hook
 *
 * Custom hook for fetching and managing GCP cost management data
 * Handles cost breakdown and forecasting for GCP accounts
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type { GcpCostDataItem, GcpCostFilters, GcpForecastDataPoint } from '../types/gcpCost';
import type { ForecastAccuracyMetrics, HistoricalData } from '../types/cost';

interface UseGcpCostDataParams {
  selectedAccountId: string | null;
  filters: GcpCostFilters;
}

/**
 * Everything that changes what the breakdown request returns, as one comparable string.
 *
 * <p>Used to suppress a duplicate fetch rather than to cache: two effects legitimately want the same
 * data on the same commit, and only comparing the request itself can tell that apart from a second,
 * genuinely different request.
 */
const requestSignature = (accountId: string, filters: GcpCostFilters): string =>
  [
    accountId,
    filters.groupBy,
    // Only read when grouping by tag, so a stale tag key must not invalidate a service breakdown.
    filters.groupBy === 'TAG' ? filters.tagKey : '',
    // /breakdown-by-tag takes no range, so the dates are not part of that request's identity.
    filters.groupBy === 'TAG' ? '' : filters.startDate,
    filters.groupBy === 'TAG' ? '' : filters.endDate,
  ].join('|');

interface UseGcpCostDataReturn {
  costData: GcpCostDataItem[];
  isLoading: boolean;
  isRefreshing: boolean;
  loadCostData: (forceRefresh?: boolean) => Promise<void>;
  loadForecastServices: () => Promise<string[]>;
  loadForecast: (service: string, periods: number) => Promise<GcpForecastDataPoint[]>;
  refreshAll: () => Promise<void>;
  /**
   * ISO code the billing account is denominated in.
   *
   * <p>Read from the backend rather than assumed. Every GCP cost component hardcoded `'₹'` and the
   * page hardcoded `currency="INR"` into three shared components — right for a rupee-billed billing
   * account and wrong by roughly ninety-fold for a dollar-billed one, which on a cost page is the
   * whole point of the number.
   */
  currency: string;
  /** How the previous forecasts turned out, or null while nothing has been scored yet. */
  forecastAccuracy: ForecastAccuracyMetrics | null;
  /** Daily trend for one dimension, for the drill-down chart. Null when it could not be read. */
  loadHistoricalTrend: (dimension: string) => Promise<HistoricalData | null>;
}

export const useGcpCostData = ({
  selectedAccountId,
  filters,
}: UseGcpCostDataParams): UseGcpCostDataReturn => {
  const [costData, setCostData] = useState<GcpCostDataItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [currency, setCurrency] = useState('USD');
  const [forecastAccuracy, setForecastAccuracy] = useState<ForecastAccuracyMetrics | null>(null);

  // Refs for stable execution
  /** Monotonic request id, so only the newest breakdown response writes state. */
  const requestIdRef = useRef(0);
  /**
   * What the last issued request was for, so the same one is never issued twice.
   *
   * <p>Replaces the previous `previousAccountRef` and `initializedRef` pair. Those tracked "has the
   * account changed" and "have we loaded once", which was enough while the filters were the only
   * thing that could not vary the request — and stopped being enough once the date range and grouping
   * became part of it. Comparing the request itself covers every reason to fetch and every reason not
   * to, with one piece of state instead of two.
   */
  const lastFetchedRef = useRef<string | null>(null);

  /**
   * Load cost breakdown data from GCP API
   */
  const loadCostData = useCallback(async (forceRefresh = false) => {
    if (!selectedAccountId) {
      setIsLoading(false);
      setCostData([]);
      return;
    }

    /*
     * Each request gets an id, and only the newest one is allowed to write state.
     *
     * This replaces an "already fetching, skipping" guard that dropped the request instead. That was
     * safe while the only thing that could trigger a fetch was an account change, and became a bug
     * once the date range and grouping did: changing a filter while a query was in flight was
     * silently ignored, leaving the reader with the previous grouping's figures under the new
     * heading and no way to tell.
     *
     * Superseding rather than queueing also fixes the ordering. BigQuery response times vary with
     * the bytes scanned, so a broad grouping requested first can land after a narrow one requested
     * second, and whichever arrived last used to win regardless of which was asked for last.
     */
    const requestId = ++requestIdRef.current;
    // Recorded here, the one place a request is actually issued, so the de-duplication check can
    // never disagree with what was really fetched.
    lastFetchedRef.current = requestSignature(selectedAccountId, filters);

    if (forceRefresh) {
      setIsRefreshing(true);
    } else {
      setIsLoading(true);
    }

    try {
      // startDate and endDate have been accepted by /breakdown since it was written and were never
      // sent, so the reader's date range had no effect on anything.
      const params = new URLSearchParams({
        accountId: selectedAccountId,
        groupBy: filters.groupBy,
        forceRefresh: String(forceRefresh),
      });
      if (filters.startDate) params.set('startDate', filters.startDate);
      if (filters.endDate) params.set('endDate', filters.endDate);
      let url = `/api/xamops/gcp/costs/breakdown?${params}`;

      if (filters.groupBy === 'TAG' && filters.tagKey) {
        // /breakdown-by-tag takes no range. Left as it is rather than faked: sending dates it
        // ignores would put a range on screen that the figures do not honour.
        url = `/api/xamops/gcp/costs/breakdown-by-tag?accountId=${encodeURIComponent(selectedAccountId)}`
          + `&tagKey=${encodeURIComponent(filters.tagKey)}&forceRefresh=${forceRefresh}`;
      }

      console.log(`[GCP Cost] Fetching cost data for account: ${selectedAccountId}, groupBy: ${filters.groupBy}`);
      const response = await fetch(url);
      // Superseded by a later request. Returning without writing state, and without clearing the
      // loading flags, so the newer request owns them.
      if (requestId !== requestIdRef.current) return;

      if (!response.ok) {
        console.error('Failed to load GCP cost data, status:', response.status);
        setCostData([]);
        return;
      }

      const data = await response.json();
      if (requestId !== requestIdRef.current) return;
      console.log('[GCP Cost] Cost data received:', Array.isArray(data) ? data.length : 0, 'items');

      if (Array.isArray(data)) {
        setCostData(data);
      } else {
        setCostData([]);
      }
    } catch (err) {
      if (requestId !== requestIdRef.current) return;
      console.error('Failed to load GCP cost data:', err);
      setCostData([]);
    } finally {
      // Only the newest request clears the spinner. A superseded one finishing first would otherwise
      // show the page as loaded while the request whose data it will actually render is still running.
      if (requestId === requestIdRef.current) {
        setIsLoading(false);
        setIsRefreshing(false);
      }
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
   * Daily cost for one service, for the drill-down chart.
   *
   * <p>`/costs/historical-trend` has existed all along and nothing called it, so clicking a row in
   * the GCP breakdown did nothing while the same click on the AWS and Azure pages opened a trend.
   *
   * <p>Returns the `{labels, costs}` shape the shared `HistoricalTrendChart` reads. The endpoint
   * answers `[{date, cost}]` — the same rows the forecast is fitted to — so the transform happens
   * here rather than in the component, which already has one contract and does not need a second.
   */
  const loadHistoricalTrend = useCallback(async (
    dimension: string,
  ): Promise<HistoricalData | null> => {
    if (!selectedAccountId) return null;

    try {
      const response = await fetch(
        `/api/xamops/gcp/costs/historical-trend?accountId=${encodeURIComponent(selectedAccountId)}`
          + `&serviceName=${encodeURIComponent(dimension)}&days=30`,
      );
      if (!response.ok) return null;

      const rows = await response.json();
      if (!Array.isArray(rows)) return null;

      const labels: string[] = [];
      const costs: number[] = [];
      for (const row of rows) {
        // Rows with no date cannot be placed on an axis, and plotting them in arrival order would
        // put them at whatever position the loop reached.
        if (row?.date == null || row?.cost == null) continue;
        labels.push(String(row.date));
        costs.push(Number(row.cost));
      }
      return { labels, costs };
    } catch (err) {
      console.error('[GCP Cost] Historical trend fetch failed:', err);
      return null;
    }
  }, [selectedAccountId]);

  /**
   * Refresh all data
   */
  const refreshAll = useCallback(async () => {
    await loadCostData(true);
  }, [loadCostData]);

  /**
   * Clear when there is no account. The fetching itself is the effect below.
   *
   * <p>This used to also trigger the initial and account-change load, guarded by refs. With the
   * filters now part of the request the signature check below covers both cases — a new account and a
   * changed filter are both just a request that has not been issued yet — so keeping the ref-guarded
   * branch as well would mean two paths deciding to fetch the same thing.
   */
  useEffect(() => {
    if (!selectedAccountId) {
      setCostData([]);
      setIsLoading(false);
      lastFetchedRef.current = null;
    }
  }, [selectedAccountId]);

  /**
   * Refetch when the reader changes the range or the grouping.
   *
   * <p>Separate from the effect above, which deliberately fires only on an account change. That one
   * guards against re-running on every render; this one has to run when the filters move, because
   * the filters are now part of the request rather than applied client-side.
   *
   * <p>Keyed on a signature of what was last actually fetched, not on "has this effect run before".
   * Both effects can want the same fetch on the same commit — the mount effect initialises and the
   * filter effect sees its dependencies for the first time — and a naive first-run guard still let
   * that through, issuing two identical BigQuery queries for every page open. BigQuery bills per byte
   * scanned, so a duplicate is a real charge rather than a wasted round trip.
   */
  useEffect(() => {
    if (!selectedAccountId) return;
    const signature = requestSignature(selectedAccountId, filters);
    if (lastFetchedRef.current === signature) return;
    loadCostData();
  }, [selectedAccountId, filters.groupBy, filters.tagKey, filters.startDate, filters.endDate, loadCostData]);

  /** The billing account's currency. Cached for a week server-side, so this is cheap to ask. */
  useEffect(() => {
    if (!selectedAccountId) {
      setCurrency('USD');
      return;
    }
    let cancelled = false;
    fetch(`/api/xamops/gcp/costs/currency?accountId=${encodeURIComponent(selectedAccountId)}`)
      .then((resp) => (resp.ok ? resp.json() : null))
      .then((body) => {
        if (!cancelled && body?.currency) setCurrency(body.currency);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [selectedAccountId]);

  /**
   * How the previous GCP forecasts turned out.
   *
   * <p>Both halves have been in place for a while and nothing joined them up: the forecast endpoint
   * records its predictions against `PROVIDER_GCP`, and the nightly evaluation scores them using
   * `GcpCostService.getDailyCostsForForecastSync`. The metrics existed and the page never asked.
   */
  useEffect(() => {
    if (!selectedAccountId) {
      setForecastAccuracy(null);
      return;
    }
    let cancelled = false;
    fetch(
      `/api/xamops/forecast/accuracy?accountId=${encodeURIComponent(selectedAccountId)}`
        + `&provider=GCP&serviceName=ALL&days=90`,
    )
      .then((resp) => (resp.ok ? resp.json() : null))
      .then((metrics) => {
        if (!cancelled) setForecastAccuracy(metrics ?? null);
      })
      .catch(() => {
        if (!cancelled) setForecastAccuracy(null);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedAccountId]);

  return {
    costData,
    isLoading,
    isRefreshing,
    loadCostData,
    loadForecastServices,
    loadForecast,
    refreshAll,
    currency,
    forecastAccuracy,
    loadHistoricalTrend,
  };
};
