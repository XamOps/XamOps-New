import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import type {
  DspmAssetDetail, DspmAssetRow, DspmError, DspmFilters, DspmPage, DspmReason, DspmStatus,
} from '../types/dspm';

const BASE = '/api/dspm';

/** Server default is 25; the table asks for 25 and lets the user widen it. */
export const DSPM_PAGE_SIZES = [25, 50, 100] as const;

/** The filter dimensions the rail renders, in the order they appear. */
export const DSPM_FILTER_DIMENSIONS = [
  'severity',
  'scan_status',
  'provider',
  'sensitive_type',
  'data_element',
  'integration_id',
  'resource_type',
  'lifecycle',
] as const;

export type DspmFilterState = Record<string, string[]>;

/**
 * Parses a DSPM error body.
 *
 * The backend answers every failure with `{code, message}` where `code` is a stable enum name. Branching
 * on the code is the contract; the message is prose and will be reworded. `apiFetchJson` throws
 * `Error("HTTP 409: <body>")`, so the JSON has to be dug back out of the message string.
 */
function parseDspmError(err: unknown): DspmError | null {
  const raw = err instanceof Error ? err.message : String(err);
  const brace = raw.indexOf('{');
  if (brace < 0) return null;
  try {
    const parsed = JSON.parse(raw.slice(brace));
    return parsed?.code ? (parsed as DspmError) : null;
  } catch {
    return null;
  }
}

/**
 * Connection state for the DSPM surface.
 *
 * Fetched before anything else and never cached. Every tab needs to know whether reads will work at all,
 * and the answer changes the moment an operator fixes a mapping — a customer refreshing after being told
 * "it's sorted" should see it immediately rather than wait out a TTL.
 */
export function useDspmStatus() {
  const [status, setStatus] = useState<DspmStatus | null>(null);
  const [loading, setLoading] = useState(true);

  const loadStatus = useCallback(async () => {
    setLoading(true);
    try {
      setStatus(await apiClient.get<DspmStatus>(`${BASE}/status`));
    } catch {
      // The status endpoint is designed not to fail, so a rejection here means the request itself did
      // not land. Reported as an unreachable upstream rather than left null, which the UI would
      // otherwise render as a permanent spinner.
      setStatus({
        ready: false, entitled: false, configured: false,
        reason: 'UPSTREAM_UNAVAILABLE',
        message: 'Could not reach the data security service. Please try again shortly.',
        projectName: null, lastVerifiedAt: null,
      });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void loadStatus(); }, [loadStatus]);

  return { status, statusLoading: loading, reloadStatus: loadStatus };
}

interface CloudQuery {
  search: string;
  sort: string;
  page: number;
  pageSize: number;
  filters: DspmFilterState;
}

function buildQuery({ search, sort, page, pageSize, filters }: CloudQuery): string {
  const params = new URLSearchParams();
  if (search.trim()) params.set('q', search.trim());
  params.set('sort', sort);
  params.set('page', String(page));
  params.set('pageSize', String(pageSize));
  // Comma-joined, which the API reads as OR within a dimension and AND across dimensions.
  for (const [dimension, values] of Object.entries(filters)) {
    if (values.length) params.set(dimension, values.join(','));
  }
  return params.toString();
}

/**
 * The Cloud datastore list, its filter facets, and asset detail.
 *
 * <h3>Why the list and the filters are fetched together</h3>
 * The upstream computes facet counts disjunctively — each dimension's counts ignore its own selection —
 * which is what keeps the rail usable after a user filters on it. That only holds if both calls carry the
 * same filter parameters, so they are issued as a pair from one effect rather than independently.
 *
 * <h3>No caching</h3>
 * Unlike the XamSecure hook, which caches against sync stamps, this reads through to a live vendor API
 * behind a short server-side Redis cache. A second client-side cache would add a second staleness window
 * over data that is already only two minutes old, for a page nobody keeps open for hours.
 */
export function useDspmCloud(enabled: boolean) {
  const [assets, setAssets] = useState<DspmPage<DspmAssetRow> | null>(null);
  const [filters, setFilters] = useState<DspmFilters | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<DspmError | null>(null);

  const [search, setSearch] = useState('');
  const [sort, setSort] = useState('-risk_score');
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState<number>(DSPM_PAGE_SIZES[0]);
  const [selected, setSelected] = useState<DspmFilterState>({});

  /** Guards against an earlier in-flight response overwriting a later one. */
  const requestSeq = useRef(0);

  const load = useCallback(async () => {
    if (!enabled) return;
    const seq = ++requestSeq.current;
    setLoading(true);
    setError(null);

    const query = buildQuery({ search, sort, page, pageSize, filters: selected });
    // The filter call ignores page and sort upstream, but sending the same string keeps the two requests
    // provably identical in their filter terms, which is what the disjunctive counting depends on.
    try {
      const [assetPage, facets] = await Promise.all([
        apiClient.get<DspmPage<DspmAssetRow>>(`${BASE}/cloud/assets?${query}`),
        apiClient.get<DspmFilters>(`${BASE}/cloud/filters?${query}`),
      ]);
      if (seq !== requestSeq.current) return;
      setAssets(assetPage);
      setFilters(facets);
    } catch (err) {
      if (seq !== requestSeq.current) return;
      setError(parseDspmError(err) ?? {
        code: 'UPSTREAM_UNAVAILABLE' as DspmReason,
        message: err instanceof Error ? err.message : 'Failed to load data security assets.',
      });
    } finally {
      if (seq === requestSeq.current) setLoading(false);
    }
  }, [enabled, search, sort, page, pageSize, selected]);

  useEffect(() => { void load(); }, [load]);

  /**
   * Toggles one filter value.
   *
   * Resets to page 1, because staying on page 7 of a narrower result set shows an empty table and reads
   * as "the filter broke".
   */
  const toggleFilter = useCallback((dimension: string, value: string) => {
    setPage(1);
    setSelected(prev => {
      const current = prev[dimension] ?? [];
      const next = current.includes(value)
        ? current.filter(v => v !== value)
        : [...current, value];
      const updated = { ...prev };
      if (next.length) updated[dimension] = next;
      else delete updated[dimension];
      return updated;
    });
  }, []);

  const clearFilters = useCallback(() => { setPage(1); setSelected({}); }, []);

  /**
   * Sets the sort field, flipping direction when the same column is chosen again.
   *
   * Descending first for every field except name: for risk score and record counts the interesting end is
   * the top, and ascending would open on a page of zeroes.
   */
  const toggleSort = useCallback((field: string) => {
    setPage(1);
    setSort(prev => {
      const descending = `-${field}`;
      if (prev === descending) return field;
      if (prev === field) return descending;
      return field === 'name' ? field : descending;
    });
  }, []);

  const updateSearch = useCallback((value: string) => { setPage(1); setSearch(value); }, []);

  const loadAsset = useCallback(async (assetId: string): Promise<DspmAssetDetail> =>
    apiClient.get<DspmAssetDetail>(`${BASE}/assets/${encodeURIComponent(assetId)}`), []);

  return {
    assets, filters, loading, error,
    search, sort, page, pageSize, selected,
    setPage, setPageSize, updateSearch, toggleFilter, clearFilters, toggleSort,
    refresh: load, loadAsset,
    activeFilterCount: Object.values(selected).reduce((n, v) => n + v.length, 0),
  };
}
