import apiClient from '../utils/apiClient';
import type { DatastoreFilterOptions } from '../types/dspmDatastores';
import type {
  EndpointFilePage, EndpointOccurrencePage, EndpointOverview, EndpointScanPage, EndpointSensitiveMetrics,
  EndpointSummary,
} from '../types/dspmDrawers';
import { withQuery } from './useDspmDatastoreDrawer';

/*
 * The Endpoint drawer's fetchers (design 6.7). All are GET: the drawer reads Matters and never changes
 * anything there. The path segment is `encodeURIComponent`-ed and null query values are left out. Each
 * promise rejects with the `apiClient` error, which callers turn into a `DspmError` with `toDspmError`
 * (`useLazyResource` does it for them).
 */

/** The drawer's filters, include values only. Each list is sent comma-joined; an empty list sends nothing. */
export interface EndpointFilterParams {
  sensitives: string[];
  documentClass: string[];
  category: string[];
  dataElement: string[];
}

export interface EndpointDrawerApi {
  overview: () => Promise<EndpointOverview>;
  /** Dimensions `sensitives`, `documentClass`, `category`, `dataElement`, without counts. */
  filterOptions: () => Promise<DatastoreFilterOptions>;
  /** The Information tab's totals. */
  summary: () => Promise<EndpointSummary>;
  /** Entity View's rows. */
  sensitives: (filters: EndpointFilterParams, showFalsePositives: boolean) => Promise<EndpointSensitiveMetrics>;
  /** An expanded entity's files with their samples. */
  occurrences: (entityName: string, skip: number, limit: number, showFalsePositives: boolean) => Promise<EndpointOccurrencePage>;
  /** File View and a document class's files. `q` is the normalised search, or null for none. */
  files: (filters: EndpointFilterParams, q: string | null, skip: number, limit: number) => Promise<EndpointFilePage>;
  scans: (skip: number, limit: number) => Promise<EndpointScanPage>;
}

const joined = (values: readonly string[]): string | null => (values.length === 0 ? null : values.join(','));

const filterQuery = (filters: EndpointFilterParams) => ({
  sensitives: joined(filters.sensitives),
  documentClass: joined(filters.documentClass),
  category: joined(filters.category),
  dataElement: joined(filters.dataElement),
});

/** The fetchers of the endpoint agent with this 24-hex source id. */
export function endpointDrawerApi(sourceId: string): EndpointDrawerApi {
  const base = `/api/dspm/datastores/${encodeURIComponent(sourceId)}/endpoint`;
  return {
    overview: () => apiClient.get<EndpointOverview>(`${base}/overview`),
    filterOptions: () => apiClient.get<DatastoreFilterOptions>(`${base}/filter-options`),
    summary: () => apiClient.get<EndpointSummary>(`${base}/summary`),
    sensitives: (filters, showFalsePositives) =>
      apiClient.get<EndpointSensitiveMetrics>(withQuery(`${base}/sensitives`, { ...filterQuery(filters), showFalsePositives })),
    occurrences: (entityName, skip, limit, showFalsePositives) =>
      apiClient.get<EndpointOccurrencePage>(
        withQuery(`${base}/occurrences`, { entityName, skip, limit, showFalsePositives }),
      ),
    files: (filters, q, skip, limit) =>
      apiClient.get<EndpointFilePage>(withQuery(`${base}/files`, { ...filterQuery(filters), q, skip, limit })),
    scans: (skip, limit) => apiClient.get<EndpointScanPage>(withQuery(`${base}/scans`, { skip, limit })),
  };
}
