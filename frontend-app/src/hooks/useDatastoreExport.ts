import { useCallback, useEffect, useRef, useState } from 'react';
import apiClient from '../utils/apiClient';
import { downloadCsv } from '../utils/csvExport';
import type { DspmError, DspmPage } from '../types/dspm';
import type { DatastoreRow } from '../types/dspmDatastores';
import type { DatastoreApiTab, DatastoreTabSlug } from '../constants/xamlensTabs';
import type { ColumnDef } from '../components/Dspm/datastores/datastoreTabConfig';
import {
  EXPORT_MAX_ROWS, EXPORT_PAGE_SIZE, buildDatastoreCsv, exportFileName,
} from '../components/Dspm/datastores/datastoreExport';
import { buildListParams, toDspmError } from './useDspmDatastores';

/** The table's state when Export was clicked. */
export interface DatastoreExportRequest {
  apiTab: DatastoreApiTab;
  slug: DatastoreTabSlug;
  /** The debounced, normalised search the list uses. */
  search: string | null;
  filterParams: ReadonlyArray<readonly [string, string]>;
  sort: string;
  /** The visible columns, in table order. */
  columns: readonly ColumnDef[];
}

/** Set when the tab holds more rows than one export takes. */
export interface DatastoreExportCap {
  exported: number;
  total: number;
}

/**
 * The Datastores export (design 6.4): every page of the current tab through the list endpoint, one
 * request at a time at 100 rows, with the tab, search, filters and sort captured at click time. It stops
 * at the total, at the last page, at an empty page or at 10,000 rows, and saves the CSV only after the
 * last page.
 *
 * Any failure saves nothing and sets `error`. Unmounting (a tab switch remounts the tab body) stops a
 * running export before its next request, and it never downloads. `start` clears the error and the cap
 * note before its first request; `dismiss` clears both (review fix R12). A click while an export runs is
 * ignored.
 */
export function useDatastoreExport() {
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState<DspmError | null>(null);
  const [capped, setCapped] = useState<DatastoreExportCap | null>(null);
  const running = useRef(false);
  const alive = useRef(true);

  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; };
  }, []);

  const start = useCallback(async (request: DatastoreExportRequest) => {
    if (running.current) return;
    running.current = true;
    setExporting(true);
    setError(null);
    setCapped(null);
    try {
      const rows: DatastoreRow[] = [];
      let total = 0;
      for (let page = 1; ; page += 1) {
        if (!alive.current) return;
        const params = buildListParams({
          apiTab: request.apiTab, page, pageSize: EXPORT_PAGE_SIZE, sort: request.sort,
          search: request.search, filterParams: request.filterParams,
        });
        const result = await apiClient.get<DspmPage<DatastoreRow>>(`/api/dspm/datastores?${params.toString()}`);
        if (!alive.current) return;
        total = result.total;
        rows.push(...result.data);
        if (result.data.length === 0 || rows.length >= total || page >= result.totalPages || rows.length >= EXPORT_MAX_ROWS) {
          break;
        }
      }
      const exported = rows.slice(0, EXPORT_MAX_ROWS);
      downloadCsv(exportFileName(request.slug, new Date()), buildDatastoreCsv(exported, request.columns, request.slug));
      if (total > EXPORT_MAX_ROWS) setCapped({ exported: exported.length, total });
    } catch (err) {
      if (alive.current) setError(toDspmError(err));
    } finally {
      running.current = false;
      if (alive.current) setExporting(false);
    }
  }, []);

  const dismiss = useCallback(() => {
    setError(null);
    setCapped(null);
  }, []);

  return { exporting, error, capped, start, dismiss };
}
