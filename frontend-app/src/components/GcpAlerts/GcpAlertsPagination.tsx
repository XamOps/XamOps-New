import React from 'react';
import { Icon } from '@iconify/react';
import { PAGE_SIZE_OPTIONS } from '../../hooks/useGcpAlertsData';

interface Props {
  page: number;
  pageCount: number;
  pageSize: number;
  setPage: (page: number) => void;
  setPageSize: (size: number) => void;
  totalResults: number;
}

/**
 * Pagination.
 *
 * The table previously rendered every matching row. At 2,124 rows that meant 2,124 table rows, each
 * with an entry animation and two interactive controls — roughly 4,250 focusable nodes — built on
 * every filter change.
 */
const GcpAlertsPagination: React.FC<Props> = ({
  page,
  pageCount,
  pageSize,
  setPage,
  setPageSize,
  totalResults,
}) => {
  const first = totalResults === 0 ? 0 : (page - 1) * pageSize + 1;
  const last = Math.min(page * pageSize, totalResults);

  return (
    <div className="px-5 py-3 border-t border-[rgba(0,0,0,0.06)] flex items-center justify-between gap-4 flex-wrap">

      <div className="flex items-center gap-3">
        <span className="text-[12px] text-[rgba(0,0,0,0.5)] tabular-nums">
          {first.toLocaleString()}–{last.toLocaleString()} of {totalResults.toLocaleString()}
        </span>

        <div className="flex items-center gap-1.5">
          <label htmlFor="gcp-alerts-page-size" className="text-[12px] text-[rgba(0,0,0,0.45)]">
            Per page
          </label>
          <select
            id="gcp-alerts-page-size"
            value={pageSize}
            onChange={e => setPageSize(Number(e.target.value))}
            className="px-2 py-1 border border-[rgba(0,0,0,0.12)] rounded-md text-[12px] font-medium text-[rgba(0,0,0,0.7)] bg-white focus:outline-none focus:ring-2 focus:ring-indigo-500"
          >
            {PAGE_SIZE_OPTIONS.map(size => (
              <option key={size} value={size}>{size}</option>
            ))}
          </select>
        </div>
      </div>

      <nav className="flex items-center gap-1" aria-label="Alerts pagination">
        <button
          onClick={() => setPage(1)}
          disabled={page <= 1}
          aria-label="First page"
          className="w-7 h-7 rounded-md flex items-center justify-center text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.05)] disabled:opacity-30 disabled:hover:bg-transparent transition-colors"
        >
          <Icon icon="mdi:page-first" width={15} />
        </button>
        <button
          onClick={() => setPage(page - 1)}
          disabled={page <= 1}
          aria-label="Previous page"
          className="w-7 h-7 rounded-md flex items-center justify-center text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.05)] disabled:opacity-30 disabled:hover:bg-transparent transition-colors"
        >
          <Icon icon="mdi:chevron-left" width={16} />
        </button>

        <span className="px-2.5 text-[12px] font-medium text-[rgba(0,0,0,0.6)] tabular-nums" aria-current="page">
          {page} / {pageCount}
        </span>

        <button
          onClick={() => setPage(page + 1)}
          disabled={page >= pageCount}
          aria-label="Next page"
          className="w-7 h-7 rounded-md flex items-center justify-center text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.05)] disabled:opacity-30 disabled:hover:bg-transparent transition-colors"
        >
          <Icon icon="mdi:chevron-right" width={16} />
        </button>
        <button
          onClick={() => setPage(pageCount)}
          disabled={page >= pageCount}
          aria-label="Last page"
          className="w-7 h-7 rounded-md flex items-center justify-center text-[rgba(0,0,0,0.5)] hover:bg-[rgba(0,0,0,0.05)] disabled:opacity-30 disabled:hover:bg-transparent transition-colors"
        >
          <Icon icon="mdi:page-last" width={15} />
        </button>
      </nav>
    </div>
  );
};

export default GcpAlertsPagination;
