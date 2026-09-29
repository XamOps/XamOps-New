import React, { useCallback, useState } from 'react';
import { Icon } from '@iconify/react';
import { useDspmCloud, DSPM_PAGE_SIZES } from '../../hooks/useDspmData';
import type { DspmAssetDetail, DspmAssetRow, DspmStatus } from '../../types/dspm';
import DspmFilterRail from './DspmFilterRail';
import DspmAssetTable from './DspmAssetTable';
import DspmAssetDrawer from './DspmAssetDrawer';
import DspmNotReady from './DspmNotReady';
import Pager from './DspmPager';

/**
 * The DSPM Cloud tab: cloud and self-hosted datastores, with the sensitive data found in them.
 *
 * Covers both `cloud` and `on_prem` categories, matching the vendor console's own Cloud screen — it lists
 * self-hosted datastores there too, which is why its count is 68 where the API's `cloud` category alone
 * returns 67.
 *
 * Not scoped by the sidebar account picker, unlike every CNAPP tab. Matters asset rows carry a Matters
 * integration id rather than a cloud account number, so there is nothing to match the picker against; the
 * `Source` filter in the rail is the equivalent control and the customer applies it themselves.
 */

/** Builds a CSV of the current page. */
function toCsv(rows: DspmAssetRow[]): string {
  const header = [
    'Name', 'Type', 'Category', 'Identifier', 'Severity', 'Risk score',
    'Sensitive records', 'Records available', 'Objects', 'Sensitive types',
    'Provider', 'Region', 'Scan status', 'Last scanned',
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
    row.risk.hasFinding ? row.risk.level : '',
    row.risk.score ?? '',
    // Exported as blank rather than 0 when the scan has not produced a result, for the same reason the
    // table shows "Not available": a zero in a spreadsheet becomes a claim that the asset is clean.
    row.sensitives.known ? row.sensitives.recordCount : '',
    row.sensitives.known ? 'yes' : 'no',
    row.sensitives.objectCount,
    row.sensitives.typeLabels.join(' / '),
    row.source.providerLabel ?? row.source.provider ?? '',
    row.source.region ?? '',
    row.scan.statusLabel,
    row.scan.lastScannedAt ? new Date(row.scan.lastScannedAt * 1000).toISOString() : '',
  ].map(escape).join(','));

  return [header.join(','), ...lines].join('\n');
}

interface Props {
  status: DspmStatus;
  onRetry: () => void;
}

const DspmCloudTab: React.FC<Props> = ({ status, onRetry }) => {
  const ready = status.ready;
  const {
    assets, filters, loading, error,
    search, sort, page, pageSize, selected, activeFilterCount,
    setPage, setPageSize, updateSearch, toggleFilter, clearFilters, toggleSort,
    refresh, loadAsset,
  } = useDspmCloud(ready);

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
    const blob = new Blob([toCsv(assets.data)], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `dspm-cloud-page${page}-${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
    URL.revokeObjectURL(url);
  };

  // Every not-ready state renders its own explanation rather than an empty table. An empty inventory and
  // an unprovisioned workspace mean opposite things on a data-security page.
  if (!ready) return <DspmNotReady status={status} onRetry={onRetry} />;

  return (
    <div className="flex gap-5">
      <DspmFilterRail
        filters={filters}
        selected={selected}
        activeCount={activeFilterCount}
        loading={loading}
        onToggle={toggleFilter}
        onClear={clearFilters}
      />

      <section className="min-w-0 flex-1 overflow-hidden rounded-2xl border border-[rgba(0,0,0,0.08)] bg-white">
        <header className="flex flex-wrap items-center gap-3 border-b border-[rgba(0,0,0,0.06)] px-5 py-4">
          <div className="flex items-center gap-2">
            <Icon icon="mdi:database-outline" width={16} className="text-[rgba(0,0,0,0.5)]" />
            <h2 className="text-sm font-bold text-[rgba(0,0,0,0.88)]">Cloud data stores</h2>
            {assets && (
              <span className="text-xs text-[rgba(0,0,0,0.4)]">
                {assets.total.toLocaleString()}
              </span>
            )}
          </div>

          <div className="ml-auto flex flex-wrap items-center gap-2">
            <div className="relative">
              <Icon
                icon="mdi:magnify" width={13}
                className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.3)]"
              />
              <input
                type="search" value={search}
                onChange={e => updateSearch(e.target.value)}
                placeholder="Search name or identifier"
                className="w-56 rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] py-1.5 pl-8 pr-3 text-xs text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
              />
            </div>
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
          page={assets} loading={loading} sort={sort}
          onSort={toggleSort} onSelect={row => { void openAsset(row); }}
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

export default DspmCloudTab;
