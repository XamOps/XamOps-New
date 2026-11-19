import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import type { DspmError } from '../types/dspm';
import type { DatastoreFilterOptions } from '../types/dspmDatastores';
import type { SaasFileLineage } from '../types/dspmDrawers';
import type {
  LineageCategories, LineageDatastorePage, LineageFilePage, LineagePolicyPage,
} from '../types/dspmLineage';
import { buildFilterParams, type DatastoreFilterState } from '../components/Dspm/datastores/datastoreFilters';
import type { LineageCategoryId } from '../components/Dspm/lineage/lineageQuery';
import { withQuery } from './useDspmDatastoreDrawer';
import { toDspmError } from './useDspmDatastores';

const BASE = '/api/dspm/lineage';

/** What one policy or datastore row asks for. */
export interface LineageRowParams {
  /** The normalised search, or null for none. */
  q: string | null;
  skip: number;
  limit: number;
  filters: DatastoreFilterState;
}

/**
 * `path?<fixed>&q&skip&limit&<filter pairs>`: the fixed parameters, then `q` when set, then the page, then
 * build 1's `buildFilterParams` pairs (`severity=CRITICAL,HIGH` or `severity_exclude=CRITICAL`).
 */
export function rowUrl(path: string, fixed: Record<string, string>, p: LineageRowParams): string {
  return withQuery(path, {
    ...fixed,
    q: p.q,
    skip: p.skip,
    limit: p.limit,
    ...Object.fromEntries(buildFilterParams(p.filters)),
  });
}

/**
 * The Data Lineage page's endpoints. All are GET: the page reads Matters and never changes anything
 * there. Each promise rejects with the `apiClient` error, which `useKeyedResource` turns into a `DspmError`.
 */
export const lineageApi = {
  categories: () => apiClient.get<LineageCategories>(`${BASE}/categories`),
  filterOptions: (level: 'policy' | 'datastore') =>
    apiClient.get<DatastoreFilterOptions>(withQuery(`${BASE}/filter-options`, { level })),
  policies: (category: LineageCategoryId, p: LineageRowParams) =>
    apiClient.get<LineagePolicyPage>(rowUrl(`${BASE}/policies`, { category }, p)),
  datastores: (policyId: string, p: LineageRowParams) =>
    apiClient.get<LineageDatastorePage>(rowUrl(`${BASE}/policies/${encodeURIComponent(policyId)}/datastores`, {}, p)),
  files: (exposureId: string, skip: number, limit: number) =>
    apiClient.get<LineageFilePage>(withQuery(`${BASE}/datastores/${encodeURIComponent(exposureId)}/files`, { skip, limit })),
  fileLineage: (fileId: string) =>
    apiClient.get<SaasFileLineage>(`${BASE}/files/${encodeURIComponent(fileId)}/lineage`),
};

/** One keyed resource as the page reads it. */
export interface KeyedResource<T> {
  /** The answer for the current key; null while idle, loading a new key, or after a failure. */
  data: T | null;
  loading: boolean;
  error: DspmError | null;
  /** Requests the current key again (the Retry action). */
  reload: () => void;
}

interface KeyedState<T> {
  /** The key the state below belongs to. */
  key: string | null;
  data: T | null;
  loading: boolean;
  error: DspmError | null;
}

/**
 * A resource identified by `key`, the request's inputs as a string. A null key is idle: no request, no
 * data. A new key clears the data and starts a request; an answer for an older key, or one that arrives
 * after unmount, is dropped (a request counter). `reload` repeats the current key and keeps its data
 * while it runs. Errors become a `DspmError` through `toDspmError`.
 *
 * `load` is read when a request starts, so callers need not memoise it.
 */
export function useKeyedResource<T>(key: string | null, load: () => Promise<T>): KeyedResource<T> {
  const latestLoad = useRef(load);
  useEffect(() => { latestLoad.current = load; });

  const [state, setState] = useState<KeyedState<T>>({ key: null, data: null, loading: false, error: null });
  const [attempt, setAttempt] = useState(0);
  const requests = useRef(0);

  useEffect(() => {
    const request = ++requests.current;
    if (key === null) {
      setState({ key: null, data: null, loading: false, error: null });
      return;
    }
    setState(prev => ({ key, data: prev.key === key ? prev.data : null, loading: true, error: null }));
    latestLoad.current().then(
      data => {
        if (request === requests.current) setState({ key, data, loading: false, error: null });
      },
      err => {
        if (request === requests.current) setState({ key, data: null, loading: false, error: toDspmError(err) });
      },
    );
    // A newer key, a reload or the unmount makes this request stale.
    return () => { requests.current += 1; };
  }, [key, attempt]);

  const reload = useCallback(() => setAttempt(n => n + 1), []);

  // Until the effect runs for a new key, the old key's state must not show through.
  if (state.key !== key) return { data: null, loading: key !== null, error: null, reload };
  return { data: state.data, loading: state.loading, error: state.error, reload };
}
