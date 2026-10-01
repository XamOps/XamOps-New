import React from 'react';
import { Icon } from '@iconify/react';

/**
 * Numbered pagination, matching the vendor console's `1 2 3 4 5 … 7 ›`.
 *
 * Numbered rather than Prev/Next because the console is numbered and because it answers a question
 * Prev/Next cannot: how much is left. With 68 datastores over 7 pages, "page 3 of 7" is the difference
 * between scanning an inventory and clicking blindly.
 *
 * Deliberately not the shared `XamSecure/Pager`: that one paginates an already-loaded array
 * client-side, whereas this drives server-side paging and is 1-based to match the API.
 */

/** Page numbers to render, with `null` marking an ellipsis gap. */
function pageWindow(current: number, total: number): Array<number | null> {
  if (total <= 7) {
    return Array.from({ length: total }, (_, i) => i + 1);
  }
  // Always show first and last, plus a window around the current page. One ellipsis on whichever side
  // is elided, so the control keeps a stable width instead of jumping as you page through.
  const pages = new Set<number>([1, total, current, current - 1, current + 1]);
  if (current <= 3) [2, 3, 4].forEach(p => pages.add(p));
  if (current >= total - 2) [total - 1, total - 2, total - 3].forEach(p => pages.add(p));

  const sorted = [...pages].filter(p => p >= 1 && p <= total).sort((a, b) => a - b);

  const out: Array<number | null> = [];
  let previous = 0;
  for (const page of sorted) {
    if (previous && page - previous > 1) out.push(null);
    out.push(page);
    previous = page;
  }
  return out;
}

interface Props {
  /** 1-based. */
  page: number;
  totalPages: number;
  onPage: (page: number) => void;
}

const DspmPager: React.FC<Props> = ({ page, totalPages, onPage }) => {
  if (totalPages <= 1) return null;

  const btn = 'inline-flex h-6 min-w-6 items-center justify-center rounded px-1.5 text-[11px] font-semibold transition-colors';

  return (
    <nav className="flex items-center gap-0.5" aria-label="Pagination">
      <button
        onClick={() => onPage(page - 1)}
        disabled={page <= 1}
        aria-label="Previous page"
        className={`${btn} text-[rgba(0,0,0,0.45)] hover:bg-[rgba(0,0,0,0.05)] disabled:opacity-30 disabled:hover:bg-transparent`}
      >
        <Icon icon="mdi:chevron-left" width={14} />
      </button>

      {pageWindow(page, totalPages).map((p, i) =>
        p === null ? (
          <span key={`gap-${i}`} className={`${btn} text-[rgba(0,0,0,0.3)]`}>…</span>
        ) : (
          <button
            key={p}
            onClick={() => onPage(p)}
            aria-current={p === page ? 'page' : undefined}
            className={`${btn} ${p === page
              ? 'bg-[rgba(0,0,0,0.88)] text-white'
              : 'text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.05)]'}`}
          >
            {p}
          </button>
        ))}

      <button
        onClick={() => onPage(page + 1)}
        disabled={page >= totalPages}
        aria-label="Next page"
        className={`${btn} text-[rgba(0,0,0,0.45)] hover:bg-[rgba(0,0,0,0.05)] disabled:opacity-30 disabled:hover:bg-transparent`}
      >
        <Icon icon="mdi:chevron-right" width={14} />
      </button>
    </nav>
  );
};

export default DspmPager;
