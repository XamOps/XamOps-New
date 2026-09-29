import React, { useCallback, useMemo, useState } from 'react';
import { Icon } from '@iconify/react';
import { useDspmAssets, DSPM_PAGE_SIZES, type DspmSurface } from '../../hooks/useDspmData';
import type { DspmAssetDetail, DspmAssetRow, DspmFilters, DspmStatus } from '../../types/dspm';
import { severityFor } from './dspmFormat';
import DspmFilterBar from './DspmFilterBar';
import DspmAssetTable from './DspmAssetTable';
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

const SURFACE_META: Record<DspmSurface, { title: string; icon: string }> = {
  cloud: { title: 'Cloud data stores', icon: 'mdi:database-outline' },
  saas: { title: 'SaaS accounts', icon: 'mdi:account-multiple-outline' },
  endpoint: { title: 'Endpoints', icon: 'mdi:laptop' },
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
    row.sourceId ?? '',
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
  const [detail, setDetail] = useState<DspmAssetDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);

  const openAsset = useCallback(async (row: DspmAssetRow) => {
    setOpenAssetId(row.id);
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

  const meta = SURFACE_META[surface];

  return (
    <div className="space-y-4">
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
        <header className="flex flex-wrap items-center gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 py-3">
          <div className="flex items-center gap-2">
            <Icon icon={meta.icon} width={16} className="text-[rgba(0,0,0,0.5)]" />
            <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">{meta.title}</h2>
            {assets && (
              <span className="text-xs text-[rgba(0,0,0,0.4)]">{assets.total.toLocaleString()}</span>
            )}
          </div>

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
              className="inline-flex items-center gap-1.5 rounded-lg border border-[rgba(0,0,0,0.12)] px-2.5 py-1.5 text-xs font-semibold text-[rgba(0,0,0,0.6)] transition-colors hover:bg-[rgba(0,0,0,0.03)]"
            >
              <Icon icon="mdi:refresh" width={13} className={loading ? 'animate-spin' : ''} />
              Refresh
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

      {openAssetId && (
        <DspmAssetDrawer
          assetId={openAssetId}
          detail={detail}
          loading={detailLoading}
          error={detailError}
          onClose={() => { setOpenAssetId(null); setDetail(null); setDetailError(null); }}
        />
      )}
    </div>
  );
};

export default DspmAssetsTab;
