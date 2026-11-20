import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import type { DspmError, DspmPage } from '../types/dspm';
import type { DatastoreFilterOptions, DatastoreKpis, DatastoreRow } from '../types/dspmDatastores';
import type { DatastoreApiTab } from '../constants/xamlensTabs';
import { DSPM_PAGE_SIZES, parseDspmError } from './useDspmData';
import {
  buildFilterParams, normalizeSearch, type DatastoreFilterState,
} from '../components/Dspm/datastores/datastoreFilters';
import type { SortField } from '../components/Dspm/datastores/datastoreTabConfig';

const BASE = '/api/dspm/datastores';

/** Typing pauses this long before the search is sent. */
export const SEARCH_DEBOUNCE_MS = 300;

/** Risk, highest first: the console's opening order on every tab. */
export const DEFAULT_DATASTORE_SORT = '-severity';

/**
 * A failed request as a `DspmError`. A body without a code (a network failure, a proxy page) counts as
 * an unreachable upstream, which the UI answers with a retry.
 */
export function toDspmError(err: unknown): DspmError {
  return parseDspmError(err) ?? {
    code: 'UPSTREAM_UNAVAILABLE',
    message: err instanceof Error ? err.message : 'Request failed.',
  };
}

/** What one list request asks for. */
export interface ListParamsInput {
  apiTab: DatastoreApiTab;
  page: number;
  pageSize: number;
  sort: string;
  /** The normalised search, or null for none. */
  search: string | null;
  /** `buildFilterParams` pairs. */
  filterParams: ReadonlyArray<readonly [string, string]>;
}

/**
 * The query of `GET /api/dspm/datastores`: `tab`, `page`, `pageSize` and `sort`, then `q` when there is a
 * search, then the filter pairs. The list and the export both build it here, so an export asks for
 * exactly the rows the table shows.
 */
export function buildListParams({ apiTab, page, pageSize, sort, search, filterParams }: ListParamsInput): URLSearchParams {
  const params = new URLSearchParams({ tab: apiTab, page: String(page), pageSize: String(pageSize), sort });
  if (search) params.set('q', search);
  for (const [key, value] of filterParams) params.set(key, value);
  return params;
}

/** Value → label of every option each dimension has returned, so a value the response drops keeps its label. */
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

/**
 * One Datastores tab's list, its filter options and every control that shapes them.
 *
 * Two effects, each with its own request sequence number so a late response never overwrites a newer
 * one: the list (tab, search, filters, sort, page, page size) and the filter options (tab, search,
 * filters). Paging and sorting therefore never refetch the options. Both build their filter terms with
 * `buildFilterParams`, which keeps the vendor's disjunctive counts consistent with the list.
 *
 * The search is sent 300 ms after typing stops, normalised by `normalizeSearch`. Any change to the
 * search, the filters, the sort or the page size returns to page 1. The caller keys the component by
 * tab, so a tab switch starts from fresh state.
 */
export function useDatastoreList(apiTab: DatastoreApiTab, enabled: boolean, initialFilters?: DatastoreFilterState) {
  const [searchInput, setSearchInput] = useState('');
  const [search, setSearch] = useState<string | null>(null);
  // A link's seed (the Dashboard's bars, design 4.7) is the first state; read on mount only.
  const [filters, setFilters] = useState<DatastoreFilterState>(initialFilters ?? {});
  const [sort, setSort] = useState<string>(DEFAULT_DATASTORE_SORT);
  const [page, setPage] = useState(1);
  const [pageSize, setPageSizeState] = useState<number>(DSPM_PAGE_SIZES[0]);

  const [list, setList] = useState<DspmPage<DatastoreRow> | null>(null);
  const [listLoading, setListLoading] = useState(false);
  const [listError, setListError] = useState<DspmError | null>(null);
  const [listAttempt, setListAttempt] = useState(0);

  const [options, setOptions] = useState<DatastoreFilterOptions | null>(null);
  const [optionsLoading, setOptionsLoading] = useState(false);
  const [optionsError, setOptionsError] = useState<DspmError | null>(null);

  const labelMemory = useRef(new Map<string, Map<string, string>>());
  const listSeq = useRef(0);
  const optionsSeq = useRef(0);

  useEffect(() => {
    const next = normalizeSearch(searchInput);
    if (next === search) return;
    const timer = window.setTimeout(() => {
      setSearch(next);
      setPage(1);
    }, SEARCH_DEBOUNCE_MS);
    return () => window.clearTimeout(timer);
  }, [searchInput, search]);

  const filterParams = useMemo(() => buildFilterParams(filters), [filters]);

  useEffect(() => {
    if (!enabled) return;
    const seq = ++listSeq.current;
    const params = buildListParams({ apiTab, page, pageSize, sort, search, filterParams });
    setListLoading(true);
    setListError(null);
    apiClient.get<DspmPage<DatastoreRow>>(`${BASE}?${params.toString()}`)
      .then(result => { if (seq === listSeq.current) setList(result); })
      .catch(err => { if (seq === listSeq.current) setListError(toDspmError(err)); })
      .finally(() => { if (seq === listSeq.current) setListLoading(false); });
  }, [enabled, apiTab, search, filterParams, sort, page, pageSize, listAttempt]);

  useEffect(() => {
    if (!enabled) return;
    const seq = ++optionsSeq.current;
    const params = new URLSearchParams({ tab: apiTab });
    if (search) params.set('q', search);
    for (const [key, value] of filterParams) params.set(key, value);
    setOptionsLoading(true);
    setOptionsError(null);
    apiClient.get<DatastoreFilterOptions>(`${BASE}/filter-options?${params.toString()}`)
      .then(result => {
        if (seq !== optionsSeq.current) return;
        rememberLabels(labelMemory.current, result);
        setOptions(result);
      })
      .catch(err => { if (seq === optionsSeq.current) setOptionsError(toDspmError(err)); })
      .finally(() => { if (seq === optionsSeq.current) setOptionsLoading(false); });
  }, [enabled, apiTab, search, filterParams]);

  /** Replaces the filter state (built with the `datastoreFilters` functions) and returns to page 1. */
  const updateFilters = useCallback((next: DatastoreFilterState) => {
    setFilters(next);
    setPage(1);
  }, []);

  /**
   * Same column flips the direction. A new column starts descending, because for risk and record counts
   * the interesting end is the top, except name, which starts A to Z.
   */
  const toggleSort = useCallback((field: SortField) => {
    setSort(prev => {
      if (prev === `-${field}`) return field;
      if (prev === field) return `-${field}`;
      return field === 'name' ? field : `-${field}`;
    });
    setPage(1);
  }, []);

  const setPageSize = useCallback((size: number) => {
    setPageSizeState(size);
    setPage(1);
  }, []);

  const reloadList = useCallback(() => setListAttempt(n => n + 1), []);

  return {
    searchInput, setSearchInput, search,
    filters, updateFilters,
    sort, toggleSort,
    page, setPage, pageSize, setPageSize,
    list, listLoading, listError, reloadList,
    options, optionsLoading, optionsError,
    labelMemory: labelMemory.current as ReadonlyMap<string, ReadonlyMap<string, string>>,
  };
}

/**
 * The KPI strip and tab counts of one tab, fetched once each time the tab changes. Search and filters
 * never change the KPIs. The last response stays until the next one arrives, so the tab strip keeps its
 * counts while a new tab loads; `kpis.tab` says which tab the values belong to.
 */
export function useDatastoreKpis(apiTab: DatastoreApiTab) {
  const [kpis, setKpis] = useState<DatastoreKpis | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<DspmError | null>(null);
  const seq = useRef(0);

  useEffect(() => {
    const current = ++seq.current;
    setLoading(true);
    setError(null);
    apiClient.get<DatastoreKpis>(`${BASE}/kpis?${new URLSearchParams({ tab: apiTab }).toString()}`)
      .then(result => { if (current === seq.current) setKpis(result); })
      .catch(err => { if (current === seq.current) setError(toDspmError(err)); })
      .finally(() => { if (current === seq.current) setLoading(false); });
  }, [apiTab]);

  return { kpis, loading, error };
}
