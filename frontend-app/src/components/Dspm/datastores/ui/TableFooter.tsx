import React from 'react';
import { Icon } from '@iconify/react';
import Pager from '../../DspmPager';
import { DSPM_PAGE_SIZES } from '../../../../hooks/useDspmData';

/** Largest offset the backend serves on the list, File View and occurrences ((page − 1) × pageSize ≤ 10,000). */
export const listPageLimit = (pageSize: number) => Math.floor(10_000 / pageSize) + 1;

/** Largest page the backend serves on Data Flow ((page − 1) × pageSize < 10,000). */
export const dataFlowPageLimit = (pageSize: number) => Math.ceil(10_000 / pageSize);

interface TableFooterProps {
  /** 1-based. */
  page: number;
  pageSize: number;
  total: number;
  onPage: (page: number) => void;
  onPageSize: (pageSize: number) => void;
  /** Defaults to 10, 25, 50, 100. */
  pageSizes?: readonly number[];
  /** The last page the server can serve (`listPageLimit` or `dataFlowPageLimit`); unset for client-side paging. */
  maxPage?: number;
}

const ARROW = 'inline-flex h-6 min-w-6 items-center justify-center rounded px-1.5 text-[rgba(0,0,0,0.45)] opacity-30';

/**
 * The footer every Datastores table shares: "Rows Per Page", "Showing a - b of N" and the numbered pager.
 *
 * With one page or none, `DspmPager` renders nothing; the console still shows its two arrows, so they
 * are drawn here, disabled.
 */
export const TableFooter: React.FC<TableFooterProps> = ({
  page, pageSize, total, onPage, onPageSize, pageSizes = DSPM_PAGE_SIZES, maxPage,
}) => {
  const pages = Math.max(1, Math.ceil(total / pageSize));
  const totalPages = maxPage === undefined ? pages : Math.min(pages, maxPage);
  const from = (page - 1) * pageSize + 1;
  const to = Math.min(page * pageSize, total);

  return (
    <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[rgba(0,0,0,0.06)] px-5 py-3 text-xs text-[rgba(0,0,0,0.5)]">
      <label className="flex items-center gap-1.5">
        <span className="text-[rgba(0,0,0,0.4)]">Rows Per Page</span>
        <select
          value={pageSize}
          onChange={e => onPageSize(Number(e.target.value))}
          className="rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] px-1.5 py-1 text-xs focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
        >
          {pageSizes.map(size => <option key={size} value={size}>{size}</option>)}
        </select>
      </label>

      <div className="flex items-center gap-3">
        {total > 0 && (
          <span>Showing {from.toLocaleString('en-US')} - {to.toLocaleString('en-US')} of {total.toLocaleString('en-US')}</span>
        )}
        {totalPages > 1 ? (
          <Pager page={page} totalPages={totalPages} onPage={onPage} />
        ) : (
          <nav className="flex items-center gap-0.5" aria-label="Pagination">
            <button type="button" disabled aria-label="Previous page" className={ARROW}>
              <Icon icon="mdi:chevron-left" width={14} />
            </button>
            <button type="button" disabled aria-label="Next page" className={ARROW}>
              <Icon icon="mdi:chevron-right" width={14} />
            </button>
          </nav>
        )}
      </div>
    </div>
  );
};
