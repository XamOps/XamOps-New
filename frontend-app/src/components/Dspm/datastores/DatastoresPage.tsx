import React, { useCallback, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import apiClient from '../../../utils/apiClient';
import type { DspmAssetDetail, DspmStatus } from '../../../types/dspm';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import { DATASTORE_TABS, type DatastoreApiTab, type DatastoreTabSlug } from '../../../constants/xamlensTabs';
import { useDatastoreKpis, useDatastoreList } from '../../../hooks/useDspmDatastores';
import { useDatastoreExport, type DatastoreExportRequest } from '../../../hooks/useDatastoreExport';
import DspmNotReady from '../DspmNotReady';
import DspmAssetDrawer from '../DspmAssetDrawer';
import { DATASTORE_TAB_CONFIG, type ColumnDef, type DatastoreTabConfig } from './datastoreTabConfig';
import { buildFilterParams } from './datastoreFilters';
import { datastoreErrorCopy } from './datastoreErrors';
import DatastoreHeader from './DatastoreHeader';
import DatastoreTabStrip from './DatastoreTabStrip';
import DatastoreKpiStrip from './DatastoreKpiStrip';
import DatastoreFilterBar from './DatastoreFilterBar';
import DatastoreTable from './DatastoreTable';
import DatastoreDrawer, { type DrawerRow } from './drawer/DatastoreDrawer';
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

/** Cloud and on-prem rows open the datastore drawer, which loads everything by the row's 24-hex source id. */
const opensDatastoreDrawer = (row: DatastoreRow): row is DrawerRow =>
  (row.category === 'cloud' || row.category === 'on_prem') && hasSourceId(row);

/** SaaS rows open the SaaS drawer, by the same 24-hex source id (design 6.3). */
const opensSaasDrawer = (row: DatastoreRow): row is DrawerRow => row.category === 'saas' && hasSourceId(row);

/** Endpoint rows open the existing asset drawer, which loads `/api/dspm/assets/{id}`. */
const opensAssetDrawer = (row: DatastoreRow) => row.category === 'endpoint' && !!row.id;

/** Any other row (an unknown category, a missing id) opens nothing and is not focusable. */
const canOpen = (row: DatastoreRow) => opensDatastoreDrawer(row) || opensSaasDrawer(row) || opensAssetDrawer(row);

const DETAIL_ERROR = 'Could not load this asset. It may have been removed since the list was loaded.';

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

  const [openRow, setOpenRow] = useState<DatastoreRow | null>(null);
  const [detail, setDetail] = useState<DspmAssetDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const detailSeq = useRef(0);

  const open = useCallback(async (row: DatastoreRow) => {
    const seq = ++detailSeq.current;
    setOpenRow(row);
    setDetail(null);
    setDetailError(null);
    setDetailLoading(false);
    // The datastore and SaaS drawers load their own data; only the endpoint drawer needs the asset detail.
    if (!opensAssetDrawer(row) || !row.id) return;
    setDetailLoading(true);
    try {
      const loaded = await apiClient.get<DspmAssetDetail>(`/api/dspm/assets/${encodeURIComponent(row.id)}`);
      if (seq === detailSeq.current) setDetail(loaded);
    } catch {
      if (seq === detailSeq.current) setDetailError(DETAIL_ERROR);
    } finally {
      if (seq === detailSeq.current) setDetailLoading(false);
    }
  }, []);

  // Stable, because the drawers put it in a keydown effect's dependencies.
  const close = useCallback(() => {
    detailSeq.current += 1;
    setOpenRow(null);
    setDetail(null);
    setDetailError(null);
    setDetailLoading(false);
  }, []);

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
          onOpen={row => { void open(row); }}
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

      {openRow && opensSaasDrawer(openRow) && (
        <SaasDrawer key={openRow.sourceId} row={openRow} onClose={close} />
      )}
      {openRow?.category === 'endpoint' && openRow.id && (
        <DspmAssetDrawer
          assetId={openRow.id}
          detail={detail}
          loading={detailLoading}
          error={detailError}
          onClose={close}
        />
      )}
      {openRow && opensDatastoreDrawer(openRow) && (
        <DatastoreDrawer key={openRow.sourceId} row={openRow} onClose={close} />
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
