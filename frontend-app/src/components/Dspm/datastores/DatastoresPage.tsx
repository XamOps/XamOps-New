import React, { useCallback, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { DspmStatus } from '../../../types/dspm';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import { DATASTORE_TABS, type DatastoreApiTab, type DatastoreTabSlug } from '../../../constants/xamlensTabs';
import { useDatastoreKpis, useDatastoreList } from '../../../hooks/useDspmDatastores';
import { useDatastoreExport, type DatastoreExportRequest } from '../../../hooks/useDatastoreExport';
import DspmNotReady from '../DspmNotReady';
import { DATASTORE_TAB_CONFIG, type ColumnDef, type DatastoreTabConfig } from './datastoreTabConfig';
import { buildFilterParams } from './datastoreFilters';
import { datastoreErrorCopy } from './datastoreErrors';
import DatastoreHeader from './DatastoreHeader';
import DatastoreTabStrip from './DatastoreTabStrip';
import DatastoreKpiStrip from './DatastoreKpiStrip';
import DatastoreFilterBar from './DatastoreFilterBar';
import DatastoreTable from './DatastoreTable';
import DatastoreDrawer, { type DrawerRow } from './drawer/DatastoreDrawer';
import EndpointDrawer from './endpoint/EndpointDrawer';
import SaasDrawer from './saas/SaasDrawer';
import { TableFooter, listPageLimit } from './ui/TableFooter';

/**
 * XamLens Datastores: one page over every Matters datastore, with the console's tabs (All, Cloud, SaaS,
 * Endpoint, On-Prem), KPI cards, filters with Include / Exclude, and a table per tab.
 *
 * Read-only. Export saves the tab's rows as CSV in the browser, from the same list endpoint; every other
 * console action (scans, row menus, bulk selection) is greyed and calls nothing.
 */

const SOURCE_ID = /^[0-9a-fA-F]{24}$/;

const hasSourceId = (row: DatastoreRow): row is DrawerRow => row.sourceId !== null && SOURCE_ID.test(row.sourceId);

/**
 * The drawer a row opens (design 6.3), each loading everything by the row's 24-hex source id: cloud and
 * on-prem rows the datastore drawer, SaaS rows the SaaS drawer, endpoint rows the Endpoint drawer. Any
 * other row (an unknown category, a missing or malformed source id) opens nothing and is not focusable.
 */
const drawerKind = (row: DatastoreRow): 'datastore' | 'saas' | 'endpoint' | null =>
  !hasSourceId(row) ? null
    : row.category === 'cloud' || row.category === 'on_prem' ? 'datastore'
    : row.category === 'saas' ? 'saas'
    : row.category === 'endpoint' ? 'endpoint'
    : null;

const canOpen = (row: DatastoreRow) => drawerKind(row) !== null;

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

const DatastoreTabBody: React.FC<{ apiTab: DatastoreApiTab; config: DatastoreTabConfig }> = ({ apiTab, config }) => {
  const {
    searchInput, setSearchInput, search, filters, updateFilters, sort, toggleSort, page, setPage, pageSize, setPageSize,
    list, listLoading, listError, reloadList, options, optionsLoading, optionsError, labelMemory,
  } = useDatastoreList(apiTab, true);

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
  const openKind = openRow ? drawerKind(openRow) : null;

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
          canOpen={canOpen}
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

      {openRow && openKind === 'datastore' && (
        <DatastoreDrawer key={openRow.sourceId} row={openRow} onClose={close} />
      )}
      {openRow && openKind === 'saas' && (
        <SaasDrawer key={openRow.sourceId} row={openRow} onClose={close} />
      )}
      {openRow && openKind === 'endpoint' && (
        <EndpointDrawer key={openRow.sourceId} row={openRow} onClose={close} />
      )}
    </div>
  );
};

/** The ready page. Not keyed by tab, so the tab strip keeps the last counts while the next tab's KPIs load. */
const ReadyDatastoresPage: React.FC<{ tab: DatastoreTabSlug }> = ({ tab }) => {
  const navigate = useNavigate();
  const apiTab = (DATASTORE_TABS.find(t => t.slug === tab) ?? DATASTORE_TABS[0]).apiTab;
  const config = DATASTORE_TAB_CONFIG[tab];
  const { kpis } = useDatastoreKpis(apiTab);

  return (
    <div className="space-y-5">
      <DatastoreHeader headerOverflow={config.headerOverflow} />
      <DatastoreTabStrip
        active={tab}
        tabCounts={kpis?.tabCounts ?? null}
        onSelect={slug => navigate(`/xamlens/datastores/${slug}`)}
      />
      <DatastoreKpiStrip cards={config.kpis} kpis={kpis?.tab === apiTab ? kpis : null} />
      {/* Keyed by tab: switching tabs resets search, filters, sort, page and hidden columns. */}
      <DatastoreTabBody key={tab} apiTab={apiTab} config={config} />
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
