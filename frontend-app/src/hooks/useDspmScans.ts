import apiClient from '../utils/apiClient';
import type { DspmPage } from '../types/dspm';
import type {
  DatastoreDatabases, DatastoreScanSettings, DatastoreSyncStatus, EndpointScanConfiguration, SaasChannel,
  SaasChannelQuery, SaasIntegrationList, SaasScanConfiguration, SaasScanUser, SaasUserQuery, ScanRun, ScanRunDatastore,
  ScanRunDatastoreQuery, ScanRunDetail, ScanRunQuery, ScanSchedule, ScanScheduleDetail, ScanScheduleQuery, ScanScope,
  ScanScopeDetail, ScanScopeQuery,
} from '../types/dspmScans';
import { withQuery } from './useDspmDatastoreDrawer';

const BASE = '/api/dspm/scan-management';
const seg = encodeURIComponent;

/** Every query value as `withQuery` takes it: unset and empty values are left out. */
function query(params: object): Record<string, string | number | boolean | null> {
  const out: Record<string, string | number | boolean | null> = {};
  for (const [key, value] of Object.entries(params)) {
    out[key] = value === undefined || value === '' ? null : (value as string | number | boolean | null);
  }
  return out;
}

/**
 * The URL of every Scan Management endpoint (S1 to S12), for `useServerList` and for `scanApi`. Ids are
 * path-encoded; the backend validates them.
 */
export const scanUrls = {
  schedules: (q: ScanScheduleQuery) => withQuery(`${BASE}/schedules`, query(q)),
  schedule: (scheduleId: string) => `${BASE}/schedules/${seg(scheduleId)}`,
  runs: (q: ScanRunQuery) => withQuery(`${BASE}/scan-history`, query(q)),
  run: (runId: string) => `${BASE}/scan-history/${seg(runId)}`,
  runDatastores: (runId: string, q: ScanRunDatastoreQuery) =>
    withQuery(`${BASE}/scan-history/${seg(runId)}/datastores`, query(q)),
  scopes: (q: ScanScopeQuery) => withQuery(`${BASE}/scan-scopes`, query(q)),
  scope: (scopeId: string) => `${BASE}/scan-scopes/${seg(scopeId)}`,
  saasIntegrations: () => `${BASE}/saas-integrations`,
  saasConfiguration: (integrationId: string) => `${BASE}/saas-integrations/${seg(integrationId)}/configuration`,
  saasUsers: (integrationId: string, q: SaasUserQuery) =>
    withQuery(`${BASE}/saas-integrations/${seg(integrationId)}/users`, query(q)),
  saasChannels: (integrationId: string, q: SaasChannelQuery) =>
    withQuery(`${BASE}/saas-integrations/${seg(integrationId)}/channels`, query(q)),
  endpointConfiguration: () => `${BASE}/endpoint-configuration`,
};

/**
 * Scan Management reads. All are GET: create, run, edit and export stay in the Matters console. Each
 * promise rejects with the `apiClient` error, which callers turn into a `DspmError` with `toDspmError`.
 */
export const scanApi = {
  schedules: (q: ScanScheduleQuery) => apiClient.get<DspmPage<ScanSchedule>>(scanUrls.schedules(q)),
  schedule: (scheduleId: string) => apiClient.get<ScanScheduleDetail>(scanUrls.schedule(scheduleId)),
  runs: (q: ScanRunQuery) => apiClient.get<DspmPage<ScanRun>>(scanUrls.runs(q)),
  run: (runId: string) => apiClient.get<ScanRunDetail>(scanUrls.run(runId)),
  runDatastores: (runId: string, q: ScanRunDatastoreQuery) =>
    apiClient.get<DspmPage<ScanRunDatastore>>(scanUrls.runDatastores(runId, q)),
  scopes: (q: ScanScopeQuery) => apiClient.get<DspmPage<ScanScope>>(scanUrls.scopes(q)),
  scope: (scopeId: string) => apiClient.get<ScanScopeDetail>(scanUrls.scope(scopeId)),
  saasIntegrations: () => apiClient.get<SaasIntegrationList>(scanUrls.saasIntegrations()),
  saasConfiguration: (integrationId: string) =>
    apiClient.get<SaasScanConfiguration>(scanUrls.saasConfiguration(integrationId)),
  saasUsers: (integrationId: string, q: SaasUserQuery) =>
    apiClient.get<DspmPage<SaasScanUser>>(scanUrls.saasUsers(integrationId, q)),
  saasChannels: (integrationId: string, q: SaasChannelQuery) =>
    apiClient.get<DspmPage<SaasChannel>>(scanUrls.saasChannels(integrationId, q)),
  endpointConfiguration: () => apiClient.get<EndpointScanConfiguration>(scanUrls.endpointConfiguration()),
};

/** The scan extras of the datastore with this 24-hex source id (the drawer's Actions menu and header). */
export function datastoreScanApi(sourceId: string) {
  const source = `/api/dspm/datastores/${seg(sourceId)}`;
  return {
    syncStatus: () => apiClient.get<DatastoreSyncStatus>(`${source}/sync-status`),
    scanSettings: () => apiClient.get<DatastoreScanSettings>(`${source}/scan-settings`),
    databases: () => apiClient.get<DatastoreDatabases>(`${source}/scan-settings/databases`),
  };
}
