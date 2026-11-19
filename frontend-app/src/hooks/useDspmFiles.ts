import { useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import type { DspmError } from '../types/dspm';
import type { DatastoreFilterOptions } from '../types/dspmDatastores';
import type { EndpointFilePage, SaasFileCopies } from '../types/dspmDrawers';
import type {
  EndpointFileActivity, EndpointFileLineage, EndpointFileOverview, SaasFileListPage,
} from '../types/dspmFiles';
import { toDspmError } from './useDspmDatastores';
import { withQuery } from './useDspmDatastoreDrawer';

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
