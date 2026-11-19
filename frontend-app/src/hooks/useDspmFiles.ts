import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import apiClient from '../utils/apiClient';
import type { DspmError } from '../types/dspm';
import type { DatastoreFilterOptions } from '../types/dspmDatastores';
import type { EndpointFilePage, SaasFileCopies } from '../types/dspmDrawers';
import type {
  EndpointFileActivity, EndpointFileLineage, EndpointFileOverview, SaasFileListPage,
} from '../types/dspmFiles';
import { DSPM_PAGE_SIZES } from './useDspmData';
import { SEARCH_DEBOUNCE_MS, toDspmError } from './useDspmDatastores';
import { withQuery } from './useDspmDatastoreDrawer';
import {
  buildFilterParams, normalizeSearch, type DatastoreFilterState,
} from '../components/Dspm/datastores/datastoreFilters';
import {
  KEYSET_START, currentCursor, keysetRange, nextPage, previousPage, type KeysetState,
} from '../components/Dspm/files/keysetPager';

/*
 * The Files page's fetchers and hooks (design 6.4 to 6.6). All are GET: the page reads Matters and never
 * changes anything there. Null query values are left out, and path segments are `encodeURIComponent`-ed.
 * Each promise rejects with the `apiClient` error, which callers turn into a `DspmError` with `toDspmError`.
 */

const BASE = '/api/dspm/files';

/** `[param, 'a,b']` pairs from `buildFilterParams`. */
type FilterPairs = ReadonlyArray<readonly [string, string]>;

/** A path with the given query values (nulls left out), then the filter pairs. */
function withFilters(path: string, params: Record<string, string | number | null>, filters: FilterPairs): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null) query.set(key, String(value));
  }
  for (const [key, value] of filters) query.set(key, value);
  const text = query.toString();
  return text ? `${path}?${text}` : path;
}

export interface SaasFilesQuery {
  limit: number;
  /** The previous page's `nextCursor`, or null for the first page. */
  cursor: string | null;
  /** The normalised search, or null for none. */
  q: string | null;
  filters: FilterPairs;
}

export interface EndpointFilesQuery {
  skip: number;
  limit: number;
  q: string | null;
  filters: FilterPairs;
}

export const filesApi = {
  saas: ({ limit, cursor, q, filters }: SaasFilesQuery) =>
    apiClient.get<SaasFileListPage>(withFilters(`${BASE}/saas`, { limit, cursor, q }, filters)),
  /** The SaaS options; a Category selection narrows Document Class to its classes. */
  saasOptions: (categories: readonly string[]) =>
    apiClient.get<DatastoreFilterOptions>(withQuery(`${BASE}/saas/filter-options`, {
      category: categories.length > 0 ? categories.join(',') : null,
    })),
  endpoint: ({ skip, limit, q, filters }: EndpointFilesQuery) =>
    apiClient.get<EndpointFilePage>(withFilters(`${BASE}/endpoint`, { skip, limit, q }, filters)),
  endpointOptions: () => apiClient.get<DatastoreFilterOptions>(`${BASE}/endpoint/filter-options`),
};

export interface EndpointFileApi {
  overview: () => Promise<EndpointFileOverview>;
  activity: () => Promise<EndpointFileActivity>;
  lineage: () => Promise<EndpointFileLineage>;
  copies: () => Promise<SaasFileCopies>;
}

/** The fetchers of the endpoint file with this UUID. */
export function endpointFileApi(fileId: string): EndpointFileApi {
  const base = `/api/dspm/endpoint-files/${encodeURIComponent(fileId)}`;
  return {
    overview: () => apiClient.get<EndpointFileOverview>(`${base}/overview`),
    activity: () => apiClient.get<EndpointFileActivity>(`${base}/activity`),
    lineage: () => apiClient.get<EndpointFileLineage>(`${base}/lineage`),
    copies: () => apiClient.get<SaasFileCopies>(`${base}/copies`),
  };
}

export type FilesKind = 'saas' | 'endpoint';

/** Value → label of every option each dimension has returned, so a value a later response drops keeps its label. */
function rememberLabels(memory: Map<string, Map<string, string>>, options: DatastoreFilterOptions) {
  for (const dimension of options.dimensions) {
    let labels = memory.get(dimension.key);
    if (!labels) {
      labels = new Map();
      memory.set(dimension.key, labels);
    }
    for (const option of dimension.options) labels.set(option.value, option.label);
  }
}

const NO_CATEGORIES: readonly string[] = [];

/**
 * A Files tab's filter options. Endpoint asks once. SaaS asks again whenever the sorted Category selection
 * changes, because Document Class narrows to the chosen categories' classes; the previous options stay on
 * screen while the next answer loads, and only the latest request's answer is applied. On a failure the
 * dropdowns read "Could not load options" while the rest of the tab works.
 */
export function useFilesFilterOptions(kind: FilesKind, categories: readonly string[] = NO_CATEGORIES) {
  const categoryKey = kind === 'saas' ? JSON.stringify([...categories].sort()) : '[]';
  const [options, setOptions] = useState<DatastoreFilterOptions | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<DspmError | null>(null);
  const labelMemory = useRef(new Map<string, Map<string, string>>());
  const seq = useRef(0);
  useEffect(() => {
    const current = ++seq.current;
    setLoading(true);
    setError(null);
    const request = kind === 'saas'
      ? filesApi.saasOptions(JSON.parse(categoryKey) as string[])
      : filesApi.endpointOptions();
    request
      .then(result => {
        if (current !== seq.current) return;
        rememberLabels(labelMemory.current, result);
        setOptions(result);
      })
      .catch(err => { if (current === seq.current) setError(toDspmError(err)); })
      .finally(() => { if (current === seq.current) setLoading(false); });
  }, [kind, categoryKey]);
  return {
    options,
    loading,
    error,
    labelMemory: labelMemory.current as ReadonlyMap<string, ReadonlyMap<string, string>>,
  };
}

/** The URL parameter that keeps a Files tab's page size. */
export const PAGE_SIZE_PARAM = 'pageSize';
const DEFAULT_PAGE_SIZE: number = DSPM_PAGE_SIZES[0];

/** The page size in the URL when it is one of 10, 25, 50 and 100, else 10. */
export function readPageSize(params: URLSearchParams): number {
  const size = Number(params.get(PAGE_SIZE_PARAM));
  return (DSPM_PAGE_SIZES as readonly number[]).includes(size) ? size : DEFAULT_PAGE_SIZE;
}

/**
 * The SaaS Files tab's list (design 6.5): search, filters, page size and keyset paging.
 *
 * `pager` is the requested position and `shown` the page on screen with its index in the cursor stack. One
 * sequence-numbered effect requests `pager`'s page; only the latest answer is applied. A success replaces
 * `shown` and clears the error; a failure sets the error and keeps `shown`, so the footer still describes
 * the rows on screen and Next or Previous start from them.
 *
 * The search is sent 300 ms after typing stops. A new search, filters or page size start again at the first
 * page with nothing on screen. The page size is read from the URL on mount and written back with `replace`,
 * left out at the default 10.
 */
export function useSaasFileList() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [searchInput, setSearchInput] = useState('');
  const [q, setQ] = useState<string | null>(null);
  const [filters, setFilters] = useState<DatastoreFilterState>({});
  const [pageSize, setPageSizeState] = useState<number>(() => readPageSize(searchParams));
  const [pager, setPager] = useState<KeysetState>(KEYSET_START);
  const [shown, setShown] = useState<{ page: SaasFileListPage; index: number } | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<DspmError | null>(null);
  const [attempt, setAttempt] = useState(0);
  const seq = useRef(0);

  /** Back to the first page with nothing on screen; the counter sends it even when the query is unchanged. */
  const restart = useCallback(() => {
    setPager(KEYSET_START);
    setShown(null);
    setError(null);
    setAttempt(n => n + 1);
  }, []);

  useEffect(() => {
    const next = normalizeSearch(searchInput);
    if (next === q) return;
    const timer = window.setTimeout(() => {
      setQ(next);
      restart();
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [searchInput, q, restart]);

  const filterParams = useMemo(() => buildFilterParams(filters), [filters]);

  useEffect(() => {
    const current = ++seq.current;
    setLoading(true);
    filesApi.saas({ limit: pageSize, cursor: currentCursor(pager), q, filters: filterParams })
      .then(body => {
        if (current !== seq.current) return;
        setShown({ page: body, index: pager.index });
        setError(null);
      })
      .catch(err => { if (current === seq.current) setError(toDspmError(err)); })
      .finally(() => { if (current === seq.current) setLoading(false); });
  }, [pager, pageSize, q, filterParams, attempt]);

  const updateFilters = useCallback((next: DatastoreFilterState) => {
    setFilters(next);
    restart();
  }, [restart]);

  const setPageSize = useCallback((size: number) => {
    setPageSizeState(size);
    restart();
    setSearchParams(prev => {
      const params = new URLSearchParams(prev);
      if (size === DEFAULT_PAGE_SIZE) params.delete(PAGE_SIZE_PARAM);
      else params.set(PAGE_SIZE_PARAM, String(size));
      return params;
    }, { replace: true });
  }, [restart, setSearchParams]);

  const range = shown ? keysetRange(shown.index, pageSize, shown.page.rows.length) : null;
  const canPrevious = !loading && shown !== null && shown.index > 0;
  const nextCursor = shown?.page.nextCursor ?? null;
  const canNext = !loading && nextCursor !== null;

  const next = () => {
    if (!canNext || shown === null || nextCursor === null) return;
    const index = shown.index;
    setPager(p => nextPage({ cursors: p.cursors, index }, nextCursor));
  };
  const previous = () => {
    if (!canPrevious || shown === null) return;
    const index = shown.index;
    setPager(p => previousPage({ cursors: p.cursors, index }));
  };
  const reload = useCallback(() => setAttempt(n => n + 1), []);

  return {
    searchInput, setSearchInput, q,
    filters, updateFilters,
    pageSize, setPageSize,
    shown, range, canPrevious, canNext, next, previous,
    loading, error, reload,
  };
}
