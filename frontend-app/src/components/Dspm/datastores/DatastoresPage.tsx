import React, { useCallback, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import apiClient from '../../../utils/apiClient';
import type { DspmAssetDetail, DspmStatus } from '../../../types/dspm';
import type { DatastoreRow } from '../../../types/dspmDatastores';
import { DATASTORE_TABS, type DatastoreApiTab, type DatastoreTabSlug } from '../../../constants/xamlensTabs';
import { useDatastoreKpis, useDatastoreList } from '../../../hooks/useDspmDatastores';
import DspmNotReady from '../DspmNotReady';
import DspmSaasDrawer from '../DspmSaasDrawer';
import DspmAssetDrawer from '../DspmAssetDrawer';
import { DATASTORE_TAB_CONFIG, type DatastoreTabConfig } from './datastoreTabConfig';
import { datastoreErrorCopy } from './datastoreErrors';
import { toLegacySaasRow } from './legacyRow';
import DatastoreHeader from './DatastoreHeader';
import DatastoreTabStrip from './DatastoreTabStrip';
import DatastoreKpiStrip from './DatastoreKpiStrip';
import DatastoreFilterBar from './DatastoreFilterBar';
import DatastoreTable from './DatastoreTable';
import DatastoreDrawer, { type DrawerRow } from './drawer/DatastoreDrawer';
import { TableFooter, listPageLimit } from './ui/TableFooter';

/**
 * XamLens Datastores: one page over every Matters datastore, with the console's tabs (All, Cloud, SaaS,
 * Endpoint, On-Prem), KPI cards, filters with Include / Exclude, and a table per tab.
 *
 * Read-only. Every console action (scans, export, row menus, bulk selection) is greyed and calls nothing.
 */

const SOURCE_ID = /^[0-9a-fA-F]{24}$/;

/** Cloud and on-prem rows open the datastore drawer, which loads everything by the row's 24-hex source id. */
const opensDatastoreDrawer = (row: DatastoreRow): row is DrawerRow =>
  (row.category === 'cloud' || row.category === 'on_prem') && row.sourceId !== null && SOURCE_ID.test(row.sourceId);

/** SaaS and endpoint rows open the existing drawers, which load `/api/dspm/assets/{id}`. */
const opensAssetDrawer = (row: DatastoreRow) => (row.category === 'saas' || row.category === 'endpoint') && !!row.id;

/** Any other row (an unknown category, a missing id) opens nothing and is not focusable. */
const canOpen = (row: DatastoreRow) => opensDatastoreDrawer(row) || opensAssetDrawer(row);

const DETAIL_ERROR = 'Could not load this asset. It may have been removed since the list was loaded.';

const DatastoreTabBody: React.FC<{ apiTab: DatastoreApiTab; config: DatastoreTabConfig }> = ({ apiTab, config }) => {
  const {
    searchInput, setSearchInput, filters, updateFilters, sort, toggleSort, page, setPage, pageSize, setPageSize,
    list, listLoading, listError, reloadList, options, optionsLoading, optionsError, labelMemory,
  } = useDatastoreList(apiTab, true);

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
    // The datastore drawer loads its own data; only the SaaS and endpoint drawers need the asset detail.
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

  const legacyRow = useMemo(() => (openRow?.category === 'saas' ? toLegacySaasRow(openRow) : null), [openRow]);
  const errorCopy = listError ? datastoreErrorCopy(listError) : null;

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
          notice={errorCopy && (
            <div role="alert" className="flex items-start gap-2 border-b border-[rgba(0,0,0,0.06)] bg-amber-50 px-5 py-3 text-xs text-amber-800">
              <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
              <span className="flex-1">{errorCopy.text}</span>
              {errorCopy.retry && (
                <button type="button" onClick={reloadList} className="font-semibold underline">Retry</button>
              )}
            </div>
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

      {openRow && legacyRow && (
        <DspmSaasDrawer
          detail={detail}
          loading={detailLoading}
          error={detailError}
          row={legacyRow}
          accountLabel={openRow.source.accountLabel ?? undefined}
          onClose={close}
        />
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
