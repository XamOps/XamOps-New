import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import type { DspmError } from '../types/dspm';
import type {
  DataFlowColumnPage, DataFlowDatabases, DataFlowSchemas, DataFlowTablePage, DatastoreAccessGraph, DatastoreFilePage,
  DatastoreOccurrencePage, DatastoreOverview, DatastoreRisks, DatastoreScanHistory, DatastoreScanProgress,
  DatastoreSensitives,
} from '../types/dspmDatastores';
import { toDspmError } from './useDspmDatastores';

/** One drawer resource as its tab reads it. */
export interface LazyResource<T> {
  /** The last answer; kept while a reload runs. */
  data: T | null;
  error: DspmError | null;
  loading: boolean;
  /** Requests the resource again (the Retry action). */
  reload: () => void;
}

/**
 * A drawer resource that loads the first time `active` is true and keeps its data afterwards, so a tab
 * fetches once however often it is shown again (NFR-2). Answers that arrive after the component
 * unmounted (the drawer closed) or after a newer request started are ignored.
 *
 * `load` is read when a request starts, so it need not be stable; the drawer is keyed by its datastore,
 * so what it loads never changes underneath it. Errors become a `DspmError` through `parseDspmError`
 * (`toDspmError`), so the tab can pick its copy by code.
 */
export function useLazyResource<T>(load: () => Promise<T>, active: boolean): LazyResource<T> {
  const [requested, setRequested] = useState(active);
  // The first activation. Set while rendering, so the request starts in the same commit; never reset.
  if (active && !requested) setRequested(true);

  const [attempt, setAttempt] = useState(0);
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<DspmError | null>(null);
  const [loading, setLoading] = useState(active);

  const latestLoad = useRef(load);
  useEffect(() => { latestLoad.current = load; });

  useEffect(() => {
    if (!requested) return;
    let current = true;
    setLoading(true);
    setError(null);
    latestLoad.current().then(
      result => {
        if (!current) return;
        setData(result);
        setLoading(false);
      },
      err => {
        if (!current) return;
        setError(toDspmError(err));
        setLoading(false);
      },
    );
    return () => { current = false; };
  }, [requested, attempt]);

  const reload = useCallback(() => setAttempt(n => n + 1), []);
  return { data, error, loading, reload };
}

const BASE = '/api/dspm/datastores';
const DATA_FLOW = '/api/dspm/data-flow';

/** `path?key=value&…`, leaving out null values. */
export function withQuery(path: string, params: Record<string, string | number | boolean | null>): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null) query.set(key, String(value));
  }
  const text = query.toString();
  return text ? `${path}?${text}` : path;
}

/**
 * Every drawer and Data Flow endpoint of one datastore. All are GET: the drawer reads Matters and never
 * changes anything there. Each promise rejects with the `apiClient` error, which callers turn into a
 * `DspmError` with `toDspmError`.
 */
export interface DatastoreDrawerApi {
  overview: () => Promise<DatastoreOverview>;
  scanStatus: () => Promise<DatastoreScanProgress>;
  sensitives: () => Promise<DatastoreSensitives>;
  /** `q` is the normalised path search, or null for none. */
  files: (page: number, pageSize: number, q: string | null) => Promise<DatastoreFilePage>;
  occurrences: (entityName: string, skip: number, limit: number, showFalsePositives: boolean) => Promise<DatastoreOccurrencePage>;
  risks: () => Promise<DatastoreRisks>;
  accessGraph: () => Promise<DatastoreAccessGraph>;
  scanHistory: () => Promise<DatastoreScanHistory>;
  dataFlowDatabases: () => Promise<DataFlowDatabases>;
  schemas: (databaseId: string) => Promise<DataFlowSchemas>;
  /** `q` only when the caller decided to search (3+ characters); null for none. */
  schemaTables: (schemaId: string, page: number, pageSize: number, q: string | null) => Promise<DataFlowTablePage>;
  databaseTables: (databaseId: string, page: number, pageSize: number, q: string | null) => Promise<DataFlowTablePage>;
  columns: (tableId: string, page: number, pageSize: number) => Promise<DataFlowColumnPage>;
}

/** The fetchers for the datastore with this 24-hex source id. */
export function datastoreDrawerApi(sourceId: string): DatastoreDrawerApi {
  const source = `${BASE}/${encodeURIComponent(sourceId)}`;
  const seg = encodeURIComponent;
  return {
    overview: () => apiClient.get<DatastoreOverview>(`${source}/overview`),
    scanStatus: () => apiClient.get<DatastoreScanProgress>(`${source}/scan-status`),
    sensitives: () => apiClient.get<DatastoreSensitives>(`${source}/sensitives`),
    files: (page, pageSize, q) =>
      apiClient.get<DatastoreFilePage>(withQuery(`${source}/sensitives/files`, { page, pageSize, q })),
    occurrences: (entityName, skip, limit, showFalsePositives) =>
      apiClient.get<DatastoreOccurrencePage>(
        withQuery(`${source}/sensitives/occurrences`, { entityName, skip, limit, showFalsePositives }),
      ),
    risks: () => apiClient.get<DatastoreRisks>(`${source}/risks`),
    accessGraph: () => apiClient.get<DatastoreAccessGraph>(`${source}/access-graph`),
    scanHistory: () => apiClient.get<DatastoreScanHistory>(`${source}/scan-history`),
    dataFlowDatabases: () => apiClient.get<DataFlowDatabases>(`${source}/data-flow/databases`),
    schemas: databaseId => apiClient.get<DataFlowSchemas>(`${DATA_FLOW}/databases/${seg(databaseId)}/schemas`),
    schemaTables: (schemaId, page, pageSize, q) =>
      apiClient.get<DataFlowTablePage>(withQuery(`${DATA_FLOW}/schemas/${seg(schemaId)}/tables`, { page, pageSize, q })),
    databaseTables: (databaseId, page, pageSize, q) =>
      apiClient.get<DataFlowTablePage>(withQuery(`${DATA_FLOW}/databases/${seg(databaseId)}/tables`, { page, pageSize, q })),
    columns: (tableId, page, pageSize) =>
      apiClient.get<DataFlowColumnPage>(withQuery(`${DATA_FLOW}/tables/${seg(tableId)}/columns`, { page, pageSize })),
  };
}
