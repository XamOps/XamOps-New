import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { DspmStatus } from '../../../types/dspm';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import { DATASTORE_TABS, type DatastoreApiTab, type DatastoreTabSlug } from '../../../constants/xamlensTabs';
import { useDatastoreKpis, useDatastoreList } from '../../../hooks/useDspmDatastores';
import { useDatastoreExport, type DatastoreExportRequest } from '../../../hooks/useDatastoreExport';
import DspmNotReady from '../DspmNotReady';
import { DATASTORE_TAB_CONFIG, type ColumnDef, type DatastoreTabConfig } from './datastoreTabConfig';
import { buildFilterParams, isSelectable, type DatastoreFilterState } from './datastoreFilters';
import { datastoreErrorCopy } from './datastoreErrors';
import DatastoreHeader from './DatastoreHeader';
import DatastoreTabStrip from './DatastoreTabStrip';
import DatastoreKpiStrip from './DatastoreKpiStrip';
import DatastoreFilterBar from './DatastoreFilterBar';
import DatastoreTable from './DatastoreTable';
import type { DrawerRow } from './drawer/DatastoreDrawer';
import { DatastoreDrawerHost, canOpenDrawer, hasSourceId } from './DatastoreDrawerHost';
import { TableFooter, listPageLimit } from './ui/TableFooter';
import ScanManagementDrawer from '../scans/ScanManagementDrawer';
import { inPageState } from '../../../utils/inPageNavigation';

/**
 * XamLens Datastores: one page over every Matters datastore, with the console's tabs (All, Cloud, SaaS,
 * Endpoint, On-Prem), KPI cards, filters with Include / Exclude, and a table per tab.
 *
 * Read-only. Export saves the tab's rows as CSV in the browser, from the same list endpoint; every other
 * console action (scans, row menus, bulk selection) is greyed and calls nothing.
 */

/**
 * The filters a link may seed (design 4.7): a Dashboard bar opens `?provider=` or, on Endpoint, `?os=`;
 * an Active Sensitive Regions "View All" opens `?region=` (brief 11).
 */
const URL_SEED_DIMENSIONS = ['provider', 'os', 'region'] as const;

/**
 * The URL's `provider`, `os` and `region` as Include filters (each a comma-separated list), keeping a value
 * only when it passes its dimension's rule (`DIMENSION_VALUE_RULES`, through `isSelectable`) and `os` only on
 * the Endpoint tab. Undefined when nothing is seeded.
 */
function urlSeedFilters(params: URLSearchParams, apiTab: DatastoreApiTab): DatastoreFilterState | undefined {
  const seed: DatastoreFilterState = {};
  for (const dimension of URL_SEED_DIMENSIONS) {
    if (dimension === 'os' && apiTab !== 'endpoint') continue;
    // A comma-separated list seeds several values (a region tab that merges codes); no value holds a comma.
    const values = (params.get(dimension) ?? '').split(',').filter(value => isSelectable(dimension, { value }));
    if (values.length === 0) continue;
    seed[dimension] = { mode: 'include', values };
  }
  return Object.keys(seed).length > 0 ? seed : undefined;
}

/** The history-state field that carries a link's seed once its parameters have left the URL. */
const SEED_STATE = 'datastoreSeed';

/**
 * The seed parameters of this entry: the URL's while they are there, else the ones the removal carried
 * in the history state. The page transition (App.tsx) can render the arriving route in the leaving page
 * first; that copy removes the parameters, and the page that then mounts reads them from the state.
 */
function seedParams(params: URLSearchParams, state: unknown): URLSearchParams {
  if (URL_SEED_DIMENSIONS.some(dimension => params.has(dimension))) return params;
  const carried = (state as Record<string, unknown> | null | undefined)?.[SEED_STATE];
  const out = new URLSearchParams();
  if (carried === null || typeof carried !== 'object') return out;
  for (const dimension of URL_SEED_DIMENSIONS) {
    const value = (carried as Record<string, unknown>)[dimension];
    if (typeof value === 'string') out.set(dimension, value);
  }
  return out;
}

/** The Dismiss button of the export's banner and note (review fix R12). */
const DismissButton: React.FC<{ onDismiss: () => void }> = ({ onDismiss }) => (
  <button
    type="button"
    aria-label="Dismiss"
    onClick={onDismiss}
    className="inline-flex flex-shrink-0 items-center rounded p-0.5 opacity-70 transition-opacity hover:opacity-100"
  >
    <Icon icon="mdi:close" width={14} />
  </button>
);

const DatastoreTabBody: React.FC<{
  apiTab: DatastoreApiTab;
  config: DatastoreTabConfig;
  /** The first filter state (a link's seed); read on mount only. */
  initialFilters?: DatastoreFilterState;
}> = ({ apiTab, config, initialFilters }) => {
  const {
    searchInput, setSearchInput, search, filters, updateFilters, sort, toggleSort, page, setPage, pageSize, setPageSize,
    list, listLoading, listError, reloadList, options, optionsLoading, optionsError, labelMemory,
  } = useDatastoreList(apiTab, true, initialFilters);

  const exporter = useDatastoreExport();
  // The last export's request, which Retry runs again.
  const exportRequest = useRef<DatastoreExportRequest | null>(null);
  const startExport = (columns: ColumnDef[]) => {
    const request: DatastoreExportRequest = {
      apiTab, slug: config.slug, search, filterParams: buildFilterParams(filters), sort, columns,
    };
    exportRequest.current = request;
    void exporter.start(request);
  };
  const retryExport = () => {
    if (exportRequest.current) void exporter.start(exportRequest.current);
  };

  // The drawers load their own data; the page keeps only the row that is open.
  const [openRow, setOpenRow] = useState<DrawerRow | null>(null);
  const open = useCallback((row: DatastoreRow) => {
    if (hasSourceId(row)) setOpenRow(row);
  }, []);
  // Stable, because the drawers put it in a keydown effect's dependencies.
  const close = useCallback(() => setOpenRow(null), []);

  const errorCopy = listError ? datastoreErrorCopy(listError) : null;
  const exportErrorCopy = exporter.error ? datastoreErrorCopy(exporter.error) : null;

  return (
    <div className="space-y-4">
      <DatastoreFilterBar
        config={config}
        searchInput={searchInput}
        onSearchInput={setSearchInput}
        options={options}
        optionsLoading={optionsLoading}
        optionsError={optionsError !== null}
        filters={filters}
        labelMemory={labelMemory}
        onFiltersChange={updateFilters}
      />

      <section className="min-w-0 rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
        <DatastoreTable
          config={config}
          page={list}
          loading={listLoading}
          sort={sort}
          onSort={toggleSort}
          canOpen={canOpenDrawer}
          onOpen={open}
          onExport={startExport}
          exporting={exporter.exporting}
          notice={(
            <>
              {errorCopy && (
                <div role="alert" className="flex items-start gap-2 border-b border-[rgba(0,0,0,0.06)] bg-amber-50 px-5 py-3 text-xs text-amber-800">
                  <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
                  <span className="flex-1">{errorCopy.text}</span>
                  {errorCopy.retry && (
                    <button type="button" onClick={reloadList} className="font-semibold underline">Retry</button>
                  )}
                </div>
              )}
              {exportErrorCopy && (
                <div role="alert" className="flex items-start gap-2 border-b border-[rgba(0,0,0,0.06)] bg-amber-50 px-5 py-3 text-xs text-amber-800">
                  <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
                  <span className="flex-1">The export could not be completed. {exportErrorCopy.text}</span>
                  {exportErrorCopy.retry && (
                    <button type="button" onClick={retryExport} className="font-semibold underline">Retry</button>
                  )}
                  <DismissButton onDismiss={exporter.dismiss} />
                </div>
              )}
              {exporter.capped && (
                <div role="status" className="flex items-start gap-2 border-b border-[rgba(0,0,0,0.06)] bg-sky-50 px-5 py-3 text-xs text-sky-800">
                  <Icon icon="mdi:information-outline" width={14} className="mt-0.5 flex-shrink-0" />
                  <span className="flex-1">
                    Exported the first {exporter.capped.exported.toLocaleString('en-US')} of{' '}
                    {exporter.capped.total.toLocaleString('en-US')} datastores.
                  </span>
                  <DismissButton onDismiss={exporter.dismiss} />
                </div>
              )}
            </>
          )}
        />

        {list && (
          <TableFooter
            page={page}
            pageSize={pageSize}
            total={list.total}
            onPage={setPage}
            onPageSize={setPageSize}
            maxPage={listPageLimit(pageSize)}
          />
        )}
      </section>

      {openRow && <DatastoreDrawerHost row={openRow} onClose={close} />}
    </div>
  );
};

/** The ready page. Not keyed by tab, so the tab strip keeps the last counts while the next tab's KPIs load. */
const ReadyDatastoresPage: React.FC<{ tab: DatastoreTabSlug }> = ({ tab }) => {
  const navigate = useNavigate();
  const apiTab = (DATASTORE_TABS.find(t => t.slug === tab) ?? DATASTORE_TABS[0]).apiTab;
  const config = DATASTORE_TAB_CONFIG[tab];
  const { kpis } = useDatastoreKpis(apiTab);

  // A link's provider / OS / region filter (design 4.7, brief 11), read once on arrival and applied to the tab it arrived
  // on; a later tab switch drops it, so coming back to that tab starts unfiltered. The parameters are then
  // removed from the URL, so a later filter change never leaves a stale one. The removal is an in-page
  // navigation (the layout keys pages by location, so a plain replace would remount the page) and carries
  // the parameters in the history state for a page that mounts after it (`seedParams`).
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const [seed, setSeed] = useState(() => ({ tab, filters: urlSeedFilters(seedParams(searchParams, location.state), apiTab) }));
  if (seed.tab !== tab && seed.filters) setSeed({ tab, filters: undefined });
  const hasSeedParams = URL_SEED_DIMENSIONS.some(dimension => searchParams.has(dimension));
  useEffect(() => {
    if (!hasSeedParams) return;
    const carried: Record<string, string> = {};
    for (const dimension of URL_SEED_DIMENSIONS) {
      const value = searchParams.get(dimension);
      if (value !== null) carried[dimension] = value;
    }
    setSearchParams(previous => {
      const next = new URLSearchParams(previous);
      for (const dimension of URL_SEED_DIMENSIONS) next.delete(dimension);
      return next;
    }, { replace: true, state: { ...inPageState(location), [SEED_STATE]: carried } });
  }, [hasSeedParams, searchParams, setSearchParams, location]);

  // Scan Management (design 6.8), opened by "Manage Scans": on the Endpoint configuration from the
  // Endpoint tab, else on SaaS. "Manage Scans" takes focus again when it closes.
  const [scanManagement, setScanManagement] = useState<{ platform: 'saas' | 'endpoint' } | null>(null);
  const manageScans = useRef<HTMLButtonElement>(null);
  const openScanManagement = useCallback(() => {
    setScanManagement({ platform: apiTab === 'endpoint' ? 'endpoint' : 'saas' });
  }, [apiTab]);
  const closeScanManagement = useCallback(() => {
    setScanManagement(null);
    manageScans.current?.focus();
  }, []);

  return (
    <div className="space-y-5">
      <DatastoreHeader
        headerOverflow={config.headerOverflow}
        onManageScans={openScanManagement}
        manageScansRef={manageScans}
      />
      <DatastoreTabStrip
        active={tab}
        tabCounts={kpis?.tabCounts ?? null}
        onSelect={slug => navigate(`/xamlens/datastores/${slug}`)}
      />
      <DatastoreKpiStrip cards={config.kpis} kpis={kpis?.tab === apiTab ? kpis : null} />
      {/* Keyed by tab: switching tabs resets search, filters, sort, page and hidden columns. */}
      <DatastoreTabBody
        key={tab}
        apiTab={apiTab}
        config={config}
        initialFilters={seed.tab === tab ? seed.filters : undefined}
      />
      {scanManagement && (
        <ScanManagementDrawer initialPlatform={scanManagement.platform} onClose={closeScanManagement} />
      )}
    </div>
  );
};

interface Props {
  tab: DatastoreTabSlug;
  status: DspmStatus;
  onRetry: () => void;
}

/**
 * Every not-ready state renders its own explanation rather than an empty table: an empty inventory and an
 * unprovisioned workspace mean opposite things on a data-security page.
 */
const DatastoresPage: React.FC<Props> = ({ tab, status, onRetry }) => {
  if (!status.ready) return <DspmNotReady status={status} onRetry={onRetry} />;
  return <ReadyDatastoresPage tab={tab} />;
};

export default DatastoresPage;
