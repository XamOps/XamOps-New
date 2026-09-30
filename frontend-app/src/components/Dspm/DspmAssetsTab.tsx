import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import { useDspmAssets, DSPM_PAGE_SIZES, type DspmSurface } from '../../hooks/useDspmData';
import type { DspmAssetDetail, DspmAssetRow, DspmFilters, DspmStatus } from '../../types/dspm';
import { severityFor } from './dspmFormat';
import DspmFilterBar from './DspmFilterBar';
import DspmAssetTable, { COLUMNS, type ColumnId } from './DspmAssetTable';
import DspmSaasDrawer from './DspmSaasDrawer';
import DspmAssetDrawer from './DspmAssetDrawer';
import DspmNotReady from './DspmNotReady';
import Pager from './DspmPager';

/**
 * One DSPM asset tab: Cloud datastores, SaaS accounts or Endpoint devices.
 *
 * The three share everything except their columns and wording. Cloud covers self-hosted datastores too,
 * matching the vendor console's Cloud screen.
 *
 * Not scoped by the sidebar account picker, unlike every CNAPP tab: Matters rows carry a Matters
 * integration id rather than a cloud account number, so the Account filter is the equivalent control.
 */

const SURFACE_TITLE: Record<DspmSurface, string> = {
  cloud: 'Cloud',
  saas: 'SaaS',
  endpoint: 'Endpoint',
};

/** The console's Columns control: which of this surface's columns are shown. */
const ColumnsMenu: React.FC<{
  surface: DspmSurface;
  hidden: ReadonlySet<ColumnId>;
  onToggle: (id: ColumnId) => void;
}> = ({ surface, hidden, onToggle }) => {
  const [open, setOpen] = useState(false);
  const wrapper = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (wrapper.current && !wrapper.current.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false); };
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  const columns = COLUMNS[surface];
  const shown = columns.filter(c => c.id === 'asset' || !hidden.has(c.id)).length;

  return (
    <div className="relative" ref={wrapper}>
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        className="inline-flex items-center gap-1.5 rounded-lg px-1.5 py-1 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:bg-[rgba(0,0,0,0.04)]"
      >
        <Icon icon="mdi:view-column-outline" width={14} />
        Columns
        <span className="rounded bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[10px] tabular-nums text-[rgba(0,0,0,0.55)]">{shown}</span>
      </button>
      {open && (
        <div className="absolute left-0 top-full z-30 mt-1 w-52 rounded-xl border border-[rgba(0,0,0,0.1)] bg-white p-1 shadow-lg">
          {columns.map(column => {
            const locked = column.id === 'asset';
            return (
              <label
                key={column.id}
                className={`flex items-center gap-2 rounded px-2 py-1.5 text-xs text-[rgba(0,0,0,0.7)] ${locked ? 'opacity-50' : 'cursor-pointer hover:bg-[rgba(0,0,0,0.03)]'}`}
              >
                <input
                  type="checkbox"
                  checked={locked || !hidden.has(column.id)}
                  disabled={locked}
                  onChange={() => onToggle(column.id)}
                  className="accent-[rgba(0,0,0,0.88)]"
                />
                {column.label}
              </label>
            );
          })}
        </div>
      )}
    </div>
  );
};

/**
 * Integration id to readable account label, taken from the Account filter's options.
 *
 * Free: the filter response is already fetched with every list page, and any row on screen matches every
 * active filter, so its integration is always among the options. Ids the backend could not resolve come
 * back labelled as themselves and are skipped, so the column falls back to region instead of hex.
 */
function accountLabelsFrom(filters: DspmFilters | null): Record<string, string> {
  const labels: Record<string, string> = {};
  for (const option of filters?.dimensions.integration_id ?? []) {
    if (option.label && option.label.toLowerCase() !== option.value.toLowerCase()) {
      labels[option.value] = option.label;
    }
  }
  return labels;
}

/** Builds a CSV of the current page. */
function toCsv(rows: DspmAssetRow[], accountLabels: Record<string, string>): string {
  const header = [
    'Name', 'Type', 'Category', 'Identifier', 'Severity', 'Risk score',
    'Sensitive records', 'Records available', 'Objects', 'Sensitive types',
    'Provider', 'Account', 'Region', 'Scan status', 'Last scanned',
  ];

  const escape = (value: string | number | null | undefined) => {
    const text = value === null || value === undefined ? '' : String(value);
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  };

  const lines = rows.map(row => [
    row.name,
    row.subtypeLabel ?? row.subtype ?? '',
    row.category,
    // The account email for SaaS, where source_id is an internal vendor id.
    row.profile?.externalId ?? row.sourceId ?? '',
    severityFor(row) ?? '',
    row.risk.score ?? '',
    // Blank rather than 0 when an unfinished scan reports zero: a zero in a spreadsheet becomes a claim
    // that the asset is clean. A non-zero count is exported, matching the table.
    row.sensitives.known || row.sensitives.recordCount > 0 ? row.sensitives.recordCount : '',
    row.sensitives.known ? 'yes' : 'no',
    row.sensitives.objectCount,
    row.sensitives.typeLabels.join(' / '),
    row.source.providerLabel ?? row.source.provider ?? '',
    row.source.account ? (accountLabels[row.source.account] ?? row.source.account) : '',
    row.source.region ?? '',
    row.scan.statusLabel,
    row.scan.lastScannedAt ? new Date(row.scan.lastScannedAt * 1000).toISOString() : '',
  ].map(escape).join(','));

  return [header.join(','), ...lines].join('\n');
}

interface Props {
  surface: DspmSurface;
  status: DspmStatus;
  onRetry: () => void;
}

const DspmAssetsTab: React.FC<Props> = ({ surface, status, onRetry }) => {
  const ready = status.ready;
  const {
    assets, filters, loading, error,
    search, sort, page, pageSize, selected, activeFilterCount,
    setPage, setPageSize, updateSearch, toggleFilter, clearFilters, clearDimension, toggleSort,
    refresh, loadAsset,
  } = useDspmAssets(surface, ready);

  const accountLabels = useMemo(() => accountLabelsFrom(filters), [filters]);

  const [openAssetId, setOpenAssetId] = useState<string | null>(null);
  const [openRow, setOpenRow] = useState<DspmAssetRow | null>(null);
  const [hidden, setHidden] = useState<ReadonlySet<ColumnId>>(new Set());
  const toggleColumn = useCallback((id: ColumnId) => {
    setHidden(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  }, []);
  const [detail, setDetail] = useState<DspmAssetDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  const openAsset = useCallback(async (row: DspmAssetRow) => {
    setOpenAssetId(row.id);
    setOpenRow(row);
    setDetail(null);
    setDetailError(null);
    setDetailLoading(true);
    try {
      setDetail(await loadAsset(row.id));
    } catch {
      setDetailError('Could not load this asset. It may have been removed since the list was loaded.');
    } finally {
      setDetailLoading(false);
    }
  }, [loadAsset]);

  // Stable, because the drawers put it in a keydown effect's dependencies.
  const closeAsset = useCallback(() => {
    setOpenAssetId(null);
    setOpenRow(null);
    setDetail(null);
    setDetailError(null);
  }, []);

  const exportCsv = () => {
    if (!assets?.data.length) return;
    const blob = new Blob([toCsv(assets.data, accountLabels)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `dspm-${surface}-page${page}-${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  // Every not-ready state renders its own explanation rather than an empty table. An empty inventory and
  // an unprovisioned workspace mean opposite things on a data-security page.
  if (!ready) return <DspmNotReady status={status} onRetry={onRetry} />;

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-lg font-bold text-[rgba(0,0,0,0.88)]">{SURFACE_TITLE[surface]}</h1>
        {surface === 'saas' && (
          // The console's SaaS page has a second tab, Data Explorer: a file-level list across every
          // account with category, document class and sharing. The API has no such listing, so the
          // tab is announced rather than built.
          <div className="mt-2 flex gap-1 border-b border-[rgba(0,0,0,0.08)]" role="tablist">
            <span role="tab" aria-selected="true" className="-mb-px border-b-2 border-[rgba(0,0,0,0.88)] px-3 py-2 text-xs font-semibold text-[rgba(0,0,0,0.88)]">
              Data Stores
            </span>
            <span
              role="tab"
              aria-selected="false"
              aria-disabled="true"
              title="Data Explorer — coming soon"
              className="-mb-px inline-flex cursor-default items-center gap-1.5 border-b-2 border-transparent px-3 py-2 text-xs font-semibold text-[rgba(0,0,0,0.35)]"
            >
              Data Explorer
              <span className="rounded-full bg-[rgba(0,0,0,0.06)] px-1.5 py-0.5 text-[9px] font-semibold uppercase tracking-wide text-[rgba(0,0,0,0.35)]">Soon</span>
            </span>
          </div>
        )}
      </div>

      <DspmFilterBar
        surface={surface}
        filters={filters}
        selected={selected}
        activeCount={activeFilterCount}
        search={search}
        onSearch={updateSearch}
        onToggle={toggleFilter}
        onClearDimension={clearDimension}
        onClearAll={clearFilters}
      />

      <section className="min-w-0 overflow-hidden rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
        <header className="flex flex-wrap items-center gap-3 border-b border-[rgba(0,0,0,0.06)] px-4 py-2.5">
          <ColumnsMenu surface={surface} hidden={hidden} onToggle={toggleColumn} />

          <div className="ml-auto flex flex-wrap items-center gap-2">
            <button
              onClick={exportCsv}
              disabled={!assets?.data.length}
              title="Export the current page as CSV"
              className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] px-2.5 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:bg-[rgba(0,0,0,0.03)] disabled:opacity-40"
            >
              <Icon icon="mdi:tray-arrow-down" width={13} />
              Export
            </button>
            <button
              onClick={refresh}
              title="Refresh"
              aria-label="Refresh"
              className="inline-flex items-center rounded-lg border border-[rgba(0,0,0,0.12)] p-1.5 text-[rgba(0,0,0,0.6)] transition-colors hover:bg-[rgba(0,0,0,0.03)]"
            >
              <Icon icon="mdi:refresh" width={13} className={loading ? 'animate-spin' : ''} />
            </button>
          </div>
        </header>

        {error && (
          <div className="flex items-start gap-2 border-b border-[rgba(0,0,0,0.06)] bg-amber-50 px-5 py-3 text-xs text-amber-800">
            <Icon icon="mdi:alert-outline" width={14} className="mt-0.5 flex-shrink-0" />
            <span className="flex-1">{error.message}</span>
            <button onClick={refresh} className="font-semibold underline">Retry</button>
          </div>
        )}

        <DspmAssetTable
          surface={surface}
          page={assets}
          loading={loading}
          sort={sort}
          accountLabels={accountLabels}
          hidden={hidden}
          onSort={toggleSort}
          onSelect={row => { void openAsset(row); }}
        />

        {assets && assets.total > 0 && (
          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[rgba(0,0,0,0.06)] px-5 py-3 text-xs text-[rgba(0,0,0,0.5)]">
            <label className="flex items-center gap-1.5">
              <span className="text-[rgba(0,0,0,0.4)]">Rows Per Page</span>
              <select
                value={pageSize}
                onChange={e => { setPage(1); setPageSize(Number(e.target.value)); }}
                className="rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] px-1.5 py-1 text-xs focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
              >
                {DSPM_PAGE_SIZES.map(size => <option key={size} value={size}>{size}</option>)}
              </select>
            </label>

            <div className="flex items-center gap-3">
              {/* "Showing 1 - 10 of 68", as the console phrases it. Page is 1-based, matching the API. */}
              <span>
                Showing {((assets.page - 1) * assets.pageSize) + 1} -{' '}
                {Math.min(assets.page * assets.pageSize, assets.total)} of{' '}
                {assets.total.toLocaleString()}
              </span>
              <Pager page={assets.page} totalPages={assets.totalPages} onPage={setPage} />
            </div>
          </div>
        )}
      </section>

      {/* SaaS opens as a side drawer over the list, as the console's does; Cloud and Endpoint as the
          full-width asset page. */}
      {openAssetId && surface === 'saas' && (
        <DspmSaasDrawer
          detail={detail}
          loading={detailLoading}
          error={detailError}
          row={openRow}
          accountLabel={openRow?.source.account ? accountLabels[openRow.source.account] : undefined}
          onClose={closeAsset}
        />
      )}
      {openAssetId && surface !== 'saas' && (
        <DspmAssetDrawer
          assetId={openAssetId}
          detail={detail}
          loading={detailLoading}
          error={detailError}
          onClose={closeAsset}
        />
      )}
    </div>
  );
};

export default DspmAssetsTab;
