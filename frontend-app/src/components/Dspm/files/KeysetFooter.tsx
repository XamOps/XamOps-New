import React from 'react';
import { Icon } from '@iconify/react';
import { DSPM_PAGE_SIZES } from '../../../hooks/useDspmData';

interface Props {
  pageSize: number;
  onPageSize: (pageSize: number) => void;
  /** The rows on screen; null while no page is on screen or the page is empty. */
  range: { from: number; to: number } | null;
  canPrevious: boolean;
  canNext: boolean;
  onPrevious: () => void;
  onNext: () => void;
}

const ARROW = 'inline-flex h-6 min-w-6 items-center justify-center rounded px-1.5 text-[rgba(0,0,0,0.45)] transition-colors hover:bg-[rgba(0,0,0,0.04)] disabled:opacity-30 disabled:hover:bg-transparent';

/**
 * The keyset table's footer: `TableFooter`'s markup with "Rows Per Page", "Showing a - b" (a keyset page
 * has no total) and the two arrows. Always rendered, also while no page is on screen, so the page-size
 * select keeps keyboard focus across a change.
 */
const KeysetFooter: React.FC<Props> = ({ pageSize, onPageSize, range, canPrevious, canNext, onPrevious, onNext }) => (
  <div className="flex flex-wrap items-center justify-between gap-3 border-t border-[rgba(0,0,0,0.06)] px-5 py-3 text-xs text-[rgba(0,0,0,0.5)]">
    <label className="flex items-center gap-1.5">
      <span className="text-[rgba(0,0,0,0.4)]">Rows Per Page</span>
      <select
        value={pageSize}
        onChange={e => onPageSize(Number(e.target.value))}
        className="rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] px-1.5 py-1 text-xs focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
      >
        {DSPM_PAGE_SIZES.map(size => <option key={size} value={size}>{size}</option>)}
      </select>
    </label>
    <div className="flex items-center gap-3">
      {range && (
        <span>Showing {range.from.toLocaleString('en-US')} - {range.to.toLocaleString('en-US')}</span>
      )}
      <nav className="flex items-center gap-0.5" aria-label="Pagination">
        <button type="button" disabled={!canPrevious} onClick={onPrevious} aria-label="Previous page" className={ARROW}>
          <Icon icon="mdi:chevron-left" width={14} />
        </button>
        <button type="button" disabled={!canNext} onClick={onNext} aria-label="Next page" className={ARROW}>
          <Icon icon="mdi:chevron-right" width={14} />
        </button>
      </nav>
    </div>
  </div>
);

export default KeysetFooter;
