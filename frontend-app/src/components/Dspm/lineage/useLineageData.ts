import { useEffect, useMemo, useRef } from 'react';
import type { DatastoreFilterOptions } from '../../../types/dspmDatastores';
import type { SaasFileLineage } from '../../../types/dspmDrawers';
import type {
  LineageDatastorePage, LineageFile, LineageFilePage, LineagePolicyPage,
} from '../../../types/dspmLineage';
import { lineageApi, useKeyedResource, type KeyedResource } from '../../../hooks/useDspmLineage';
import { buildFilterParams, type DatastoreFilterState } from '../datastores/datastoreFilters';
import {
  FILE_SEARCH_LIMIT, PAGE_SIZE, SEARCH_LIMIT, openLevel, setPage, type LineageQuery, type RowLevel,
} from './lineageQuery';

/** The More-filters state of the two filterable rows. */
export type LineageFilters = Record<'policy' | 'datastore', DatastoreFilterState>;

export interface LineageData {
  policies: KeyedResource<LineagePolicyPage>;
  datastores: KeyedResource<LineageDatastorePage>;
  files: KeyedResource<LineageFilePage>;
  /** The file nodes to draw: the loaded page, or a search's matches among the first 100; null until loaded. */
  fileRows: LineageFile[] | null;
  lineage: KeyedResource<SaasFileLineage>;
  /** The open level's filter options (policy and datastore levels only). */
  options: KeyedResource<DatastoreFilterOptions>;
}

/** A row's request window: a search asks for its first `searchLimit` rows, else the page of 7. */
const rowWindow = (q: string, page: number, searchLimit: number) =>
  q !== '' ? { skip: 0, limit: searchLimit } : { skip: page * PAGE_SIZE, limit: PAGE_SIZE };

/**
 * Every request of the open investigation (build 4 design 6.5). Each resource's key is the JSON of exactly
 * its request's inputs (the filters as the parameter pairs they send), so a change of one input, and only that, sends a request. Searched files are
 * filtered by name in the browser. A row answering `available: false` (the switch was turned off under
 * the open page) calls `onUnavailable`; a row that comes back empty on a page above 0 without a search (a
 * stale URL) goes back to page 0 through `onQuery`.
 */
export function useLineageData(
  query: LineageQuery,
  filters: LineageFilters,
  onQuery: (query: LineageQuery) => void,
  onUnavailable: () => void,
): LineageData {
  const { category, policy, datastore, file, page, q } = query;
  const open = openLevel(query);

  const policyWindow = rowWindow(q.policy, page.policy, SEARCH_LIMIT);
  const policies = useKeyedResource<LineagePolicyPage>(
    category === null ? null : JSON.stringify(['policies', category, q.policy, policyWindow, buildFilterParams(filters.policy)]),
    () => lineageApi.policies(category!, { q: q.policy || null, ...policyWindow, filters: filters.policy }),
  );

  const datastoreWindow = rowWindow(q.datastore, page.datastore, SEARCH_LIMIT);
  const datastores = useKeyedResource<LineageDatastorePage>(
    policy === null ? null : JSON.stringify(['datastores', policy, q.datastore, datastoreWindow, buildFilterParams(filters.datastore)]),
    () => lineageApi.datastores(policy!, { q: q.datastore || null, ...datastoreWindow, filters: filters.datastore }),
  );

  const fileWindow = rowWindow(q.file, page.file, FILE_SEARCH_LIMIT);
  const files = useKeyedResource<LineageFilePage>(
    datastore === null ? null : JSON.stringify(['files', datastore, fileWindow]),
    () => lineageApi.files(datastore!, fileWindow.skip, fileWindow.limit),
  );

  const lineage = useKeyedResource<SaasFileLineage>(
    file === null ? null : JSON.stringify(['lineage', file]),
    () => lineageApi.fileLineage(file!),
  );

  const optionsLevel = open === 'policy' || open === 'datastore' ? open : null;
  const options = useKeyedResource<DatastoreFilterOptions>(
    optionsLevel === null ? null : JSON.stringify(['options', optionsLevel]),
    () => lineageApi.filterOptions(optionsLevel!),
  );

  const fileRows = useMemo(() => {
    if (!files.data) return null;
    const needle = q.file.toLowerCase();
    if (needle === '') return files.data.rows;
    return files.data.rows.filter(row => row.name?.toLowerCase().includes(needle));
  }, [files.data, q.file]);

  // The callbacks and the query as they are when an answer arrives.
  const latest = useRef({ query, onQuery, onUnavailable });
  useEffect(() => { latest.current = { query, onQuery, onUnavailable }; });

  const unavailable = [policies.data, datastores.data, files.data].some(d => d !== null && !d.available);
  useEffect(() => {
    if (unavailable) latest.current.onUnavailable();
  }, [unavailable]);

  const stale = (level: RowLevel, data: { available: boolean; rows: unknown[] } | null) =>
    data !== null && data.available && data.rows.length === 0 && page[level] > 0 && q[level] === '';
  const stalePolicy = stale('policy', policies.data);
  const staleDatastore = stale('datastore', datastores.data);
  const staleFile = stale('file', files.data);
  useEffect(() => {
    let next = latest.current.query;
    if (stalePolicy) next = setPage(next, 'policy', 0);
    if (staleDatastore) next = setPage(next, 'datastore', 0);
    if (staleFile) next = setPage(next, 'file', 0);
    if (next !== latest.current.query) latest.current.onQuery(next);
  }, [stalePolicy, staleDatastore, staleFile]);

  return { policies, datastores, files, fileRows, lineage, options };
}
