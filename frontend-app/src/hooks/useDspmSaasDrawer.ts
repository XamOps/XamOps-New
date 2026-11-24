import apiClient from '../utils/apiClient';
import type {
  SaasDrawerHeader, SaasFileActivity, SaasFileCopies, SaasFileLineage, SaasFileOverview, SaasFilePage, SaasRecords,
  SaasRecordsView, SaasScanSummary,
} from '../types/dspmDrawers';
import { withQuery } from './useDspmDatastoreDrawer';

/*
 * The SaaS drawer's and the SaaS file drawer's fetchers (design 6.5, 6.6). All are GET: the drawers read
 * Matters and never change anything there. Path segments are `encodeURIComponent`-ed and null query
 * values left out. Each promise rejects with the `apiClient` error, which callers turn into a `DspmError`
 * with `toDspmError` (`useLazyResource` does it for them).
 */

/** One page of an expanded Sensitives row's files: exactly one of `dataElement` and `documentClass`. */
export interface SaasFileQuery {
  dataElement?: string;
  documentClass?: string;
  /** The normalised search, or null for none. */
  q: string | null;
  skip: number;
  limit: number;
}

export interface SaasDrawerApi {
  header: () => Promise<SaasDrawerHeader>;
  records: (view: SaasRecordsView) => Promise<SaasRecords>;
  scanSummary: () => Promise<SaasScanSummary>;
  files: (query: SaasFileQuery) => Promise<SaasFilePage>;
}

/** The fetchers of the SaaS datastore with this 24-hex source id. */
export function saasDrawerApi(sourceId: string): SaasDrawerApi {
  const base = `/api/dspm/datastores/${encodeURIComponent(sourceId)}/saas`;
  return {
    header: () => apiClient.get<SaasDrawerHeader>(`${base}/detail`),
    records: view => apiClient.get<SaasRecords>(withQuery(`${base}/records`, { view })),
    scanSummary: () => apiClient.get<SaasScanSummary>(`${base}/scan-summary`),
    files: ({ dataElement, documentClass, q, skip, limit }) =>
      apiClient.get<SaasFilePage>(withQuery(`${base}/files`, {
        dataElement: dataElement ?? null, documentClass: documentClass ?? null, q, limit, skip,
      })),
  };
}

export interface SaasFileApi {
  overview: () => Promise<SaasFileOverview>;
  activity: () => Promise<SaasFileActivity>;
  lineage: () => Promise<SaasFileLineage>;
  /** File Copies and Related Files share it (review fix R8). */
  copies: () => Promise<SaasFileCopies>;
}

/** The fetchers of the SaaS file with this 24-hex file id. */
export function saasFileApi(fileId: string): SaasFileApi {
  const base = `/api/dspm/saas-files/${encodeURIComponent(fileId)}`;
  return {
    overview: () => apiClient.get<SaasFileOverview>(`${base}/overview`),
    activity: () => apiClient.get<SaasFileActivity>(`${base}/activity`),
    lineage: () => apiClient.get<SaasFileLineage>(`${base}/lineage`),
    copies: () => apiClient.get<SaasFileCopies>(`${base}/copies`),
  };
}
