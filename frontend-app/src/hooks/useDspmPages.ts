import apiClient from '../utils/apiClient';
import type { AssistList, DocumentClassList, DspmDashboard, RemediationActivity } from '../types/dspmPages';
import { withQuery } from './useDspmDatastoreDrawer';

const BASE = '/api/dspm';

/** What the Remediation list asks for. `skip` is a row offset; `limit` up to 100. */
export interface RemediationQueryParams {
  q?: string | null;
  status?: string | null;
  actionType?: string | null;
  skip: number;
  limit: number;
}

/**
 * The XamLens page endpoints (Dashboard, Document Classes, Assists, Remediation). All are GET: the pages
 * read Matters and never change anything there. Each promise rejects with the `apiClient` error, which
 * callers turn into a `DspmError` with `toDspmError`.
 */
export const pagesApi = {
  dashboard: () => apiClient.get<DspmDashboard>(`${BASE}/dashboard`),
  documentClasses: () => apiClient.get<DocumentClassList>(`${BASE}/document-classes`),
  assists: () => apiClient.get<AssistList>(`${BASE}/assists`),
  remediationUrl: (params: RemediationQueryParams) => withQuery(`${BASE}/remediation/activity`, {
    q: params.q ?? null,
    status: params.status ?? null,
    actionType: params.actionType ?? null,
    skip: params.skip,
    limit: params.limit,
  }),
  remediation: (params: RemediationQueryParams) =>
    apiClient.get<RemediationActivity>(pagesApi.remediationUrl(params)),
};
