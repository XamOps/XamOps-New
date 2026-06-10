import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Icon } from '@iconify/react';
import ScriptCard from './ScriptCard';
import { DevOpsScript, PaginationState } from '../../types/devopsInTheBox';

/**
 * ScriptGrid — responsive, paginated grid of {@link ScriptCard}s.
 *
 * Purely presentational + lightly stateful: it receives the current page of
 * scripts plus loading / pagination metadata and renders them in a responsive
 * grid (1 column on mobile, 2 on tablet, 3 on desktop). It never fetches data
 * itself; loading more / changing pages is delegated to the parent via
 * callbacks.
 *
 * It supports two mutually-exclusive paging strategies, selected via
 * `paginationMode`:
 *  - `infinite` (default): an IntersectionObserver sentinel auto-loads the next
 *    page as it scrolls into view, with a "Load More" button as a fallback (and
 *    for environments without IntersectionObserver support).
 *  - `paged`: classic page-number controls (prev / numbered pages / next).
 *
 * While `loading` it shows skeleton placeholder cards; when there are no
 * results it shows an empty state with a "Reset filters" action.
 *
 * Requirements: REQ-2.3.4 (pagination / infinite scroll), REQ-2.5.1 (script
 * cards grid), REQ-2.5.4 (empty state), REQ-2.6.2 (responsive, performant grid)
 */

const DEFAULT_SKELETON_COUNT = 6;
// How far the user must scroll before the scroll-to-top button appears.
const SCROLL_TOP_THRESHOLD = 400;

export type PaginationMode = 'infinite' | 'paged';

export interface ScriptGridProps {
  /** The current page of scripts to render. */
  scripts: DevOpsScript[];
  /** Whether a fetch is in flight (drives skeletons / disabled controls). */
  loading?: boolean;
  /** Whether more scripts can be loaded (infinite mode). */
  hasMore?: boolean;
  /** Load the next page of results (infinite mode). */
  onLoadMore?: () => void;
  /** Open the detail view for a script (wired to ScriptCard's onOpenDetail). */
  onScriptClick: (script: DevOpsScript) => void;
  /** Toggle a script's favorite state. */
  onToggleFavorite: (script: DevOpsScript) => void;
  /** Clear all active filters from the empty state. */
  onResetFilters?: () => void;
  /** Paging strategy. Defaults to `infinite`. */
  paginationMode?: PaginationMode;
  /** Pagination metadata, required for `paged` mode page controls. */
  pagination?: PaginationState;
  /** Change to a specific page (1-based), used by `paged` mode controls. */
  onPageChange?: (page: number) => void;
  /** Number of skeleton cards to show while loading. Defaults to 6. */
  skeletonCount?: number;
}

// ─── Pagination helpers ───────────────────────────────────────────────────────

/**
 * Build a windowed list of page numbers (1-based) around `current`, inserting
 * `'…'` gap markers. Always shows the first and last page.
 *
 * e.g. total=10, current=5 → [1, '…', 4, 5, 6, '…', 10]
 */
export function buildPageItems(current: number, totalPages: number): Array<number | '…'> {
  if (totalPages <= 1) return totalPages === 1 ? [1] : [];
  const items: Array<number | '…'> = [];
  const window = 1; // pages on each side of current
  const pages = new Set<number>([1, totalPages]);
  for (let p = current - window; p <= current + window; p++) {
    if (p >= 1 && p <= totalPages) pages.add(p);
  }
  const sorted = Array.from(pages).sort((a, b) => a - b);
  let prev = 0;
  for (const p of sorted) {
    if (prev && p - prev > 1) items.push('…');
    items.push(p);
    prev = p;
  }
  return items;
}

function totalPagesOf(pagination?: PaginationState): number {
  if (!pagination || pagination.pageSize <= 0) return 0;
  return Math.max(1, Math.ceil(pagination.totalCount / pagination.pageSize));
}

// ─── Skeleton ─────────────────────────────────────────────────────────────────

const SkeletonCard: React.FC = () => (
  <div
    aria-hidden="true"
    className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-5 animate-pulse"
  >
    <div className="flex items-start justify-between mb-3">
      <div className="w-10 h-10 bg-[rgba(0,0,0,0.06)] rounded-lg" />
      <div className="w-20 h-6 bg-[rgba(0,0,0,0.06)] rounded-full" />
    </div>
    <div className="h-4 bg-[rgba(0,0,0,0.08)] rounded w-3/4 mb-2" />
    <div className="h-3 bg-[rgba(0,0,0,0.05)] rounded w-full mb-1.5" />
    <div className="h-3 bg-[rgba(0,0,0,0.05)] rounded w-5/6 mb-4" />
    <div className="flex items-center gap-2 mb-4">
      <div className="h-4 w-12 bg-[rgba(0,0,0,0.05)] rounded" />
      <div className="h-4 w-12 bg-[rgba(0,0,0,0.05)] rounded" />
    </div>
    <div className="flex items-center gap-3 pt-3 border-t border-[rgba(0,0,0,0.06)]">
      <div className="h-3 w-10 bg-[rgba(0,0,0,0.05)] rounded" />
      <div className="h-3 w-14 bg-[rgba(0,0,0,0.05)] rounded" />
    </div>
  </div>
);

// ─── Component ──────────────────────────────────────────────────────────────────

const GRID_CLASS = 'grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4';

const ScriptGrid: React.FC<ScriptGridProps> = ({
  scripts,
  loading = false,
  hasMore = false,
  onLoadMore,
  onScriptClick,
  onToggleFavorite,
  onResetFilters,
  paginationMode = 'infinite',
  pagination,
  onPageChange,
  skeletonCount = DEFAULT_SKELETON_COUNT,
}) => {
  const sentinelRef = useRef<HTMLDivElement>(null);
  const [showScrollTop, setShowScrollTop] = useState(false);

  const isInfinite = paginationMode === 'infinite';
  const hasScripts = scripts.length > 0;
  // Initial load = loading with nothing yet rendered; show a full skeleton grid.
  const isInitialLoading = loading && !hasScripts;

  // ── Infinite scroll via IntersectionObserver (with Load More fallback) ──
  useEffect(() => {
    if (!isInfinite || !hasMore || loading) return;
    const node = sentinelRef.current;
    // Guard environments without IntersectionObserver (e.g. some test runners):
    // the visible "Load More" button remains as a manual fallback.
    if (!node || typeof IntersectionObserver === 'undefined') return;

    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((e) => e.isIntersecting)) {
          onLoadMore?.();
        }
      },
      { rootMargin: '200px' }
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, [isInfinite, hasMore, loading, onLoadMore]);

  // ── Scroll-to-top button visibility ──
  useEffect(() => {
    const onScroll = () => {
      const y = window.scrollY || document.documentElement.scrollTop || 0;
      setShowScrollTop(y > SCROLL_TOP_THRESHOLD);
    };
    onScroll();
    window.addEventListener('scroll', onScroll, { passive: true });
    return () => window.removeEventListener('scroll', onScroll);
  }, []);

  const scrollToTop = useCallback(() => {
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }, []);

  const totalPages = useMemo(() => totalPagesOf(pagination), [pagination]);
  const currentPage = pagination?.page ?? 1;
  const pageItems = useMemo(
    () => buildPageItems(currentPage, totalPages),
    [currentPage, totalPages]
  );

  const goToPage = useCallback(
    (page: number) => {
      if (!onPageChange || page < 1 || page > totalPages || page === currentPage) return;
      onPageChange(page);
    },
    [onPageChange, totalPages, currentPage]
  );

  // ── Empty state ──
  if (!loading && !hasScripts) {
    return (
      <div
        role="status"
        className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl py-16 shadow-sm text-center"
      >
        <div className="w-12 h-12 bg-[rgba(0,0,0,0.03)] border border-[rgba(0,0,0,0.06)] rounded-xl flex items-center justify-center mx-auto mb-3">
          <Icon icon="mdi:magnify" width={24} color="rgba(0,0,0,0.2)" />
        </div>
        <p className="text-[14px] font-bold text-[rgba(0,0,0,0.55)]">No scripts found</p>
        <p className="text-[12px] text-[rgba(0,0,0,0.35)] mt-1">
          Try adjusting your search or filters to find what you’re looking for.
        </p>
        {onResetFilters && (
          <button
            type="button"
            onClick={onResetFilters}
            className="inline-flex items-center gap-1.5 mt-4 px-4 py-2 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors"
          >
            <Icon icon="mdi:filter-off-outline" width={13} />
            Reset filters
          </button>
        )}
      </div>
    );
  }

  // ── Initial loading: full skeleton grid ──
  if (isInitialLoading) {
    return (
      <div className={GRID_CLASS} aria-busy="true" data-testid="script-grid-skeletons">
        {Array.from({ length: skeletonCount }).map((_, i) => (
          <SkeletonCard key={i} />
        ))}
      </div>
    );
  }

  return (
    <>
      {/* Cards grid */}
      <div className={GRID_CLASS} data-testid="script-grid">
        {scripts.map((script, idx) => (
          <ScriptCard
            key={script.id}
            script={script}
            index={idx}
            onOpenDetail={onScriptClick}
            onToggleFavorite={onToggleFavorite}
          />
        ))}

        {/* Append-loading skeletons (loading more on top of existing results) */}
        {loading &&
          isInfinite &&
          Array.from({ length: Math.min(3, skeletonCount) }).map((_, i) => (
            <SkeletonCard key={`more-skeleton-${i}`} />
          ))}
      </div>

      {/* Infinite scroll controls */}
      {isInfinite && hasMore && (
        <div className="flex flex-col items-center mt-6">
          {/* Sentinel watched by the IntersectionObserver */}
          <div ref={sentinelRef} aria-hidden="true" className="h-px w-full" />
          <button
            type="button"
            onClick={() => onLoadMore?.()}
            disabled={loading}
            className="inline-flex items-center gap-2 px-5 py-2.5 text-[13px] font-semibold text-[rgba(0,0,0,0.7)] bg-white border border-[rgba(0,0,0,0.12)] rounded-xl hover:bg-[rgba(0,0,0,0.02)] transition-colors disabled:opacity-60 disabled:cursor-not-allowed"
          >
            {loading ? (
              <>
                <Icon icon="mdi:loading" width={15} className="animate-spin" />
                Loading…
              </>
            ) : (
              <>
                <Icon icon="mdi:chevron-down" width={15} />
                Load more
              </>
            )}
          </button>
        </div>
      )}

      {/* Paged controls */}
      {!isInfinite && totalPages > 1 && (
        <nav
          aria-label="Pagination"
          className="flex items-center justify-center flex-wrap gap-1.5 mt-6"
        >
          <button
            type="button"
            onClick={() => goToPage(currentPage - 1)}
            disabled={currentPage <= 1 || loading}
            aria-label="Previous page"
            className="inline-flex items-center gap-1 px-3 py-2 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            <Icon icon="mdi:chevron-left" width={14} />
            Prev
          </button>

          {pageItems.map((item, idx) =>
            item === '…' ? (
              <span
                key={`gap-${idx}`}
                aria-hidden="true"
                className="px-2 text-[12px] text-[rgba(0,0,0,0.35)] select-none"
              >
                …
              </span>
            ) : (
              <button
                key={item}
                type="button"
                onClick={() => goToPage(item)}
                disabled={loading}
                aria-label={`Page ${item}`}
                aria-current={item === currentPage ? 'page' : undefined}
                className={`min-w-[36px] px-3 py-2 text-[12px] font-semibold rounded-lg border transition-colors disabled:cursor-not-allowed ${
                  item === currentPage
                    ? 'bg-indigo-600 text-white border-transparent'
                    : 'bg-white text-[rgba(0,0,0,0.65)] border-[rgba(0,0,0,0.12)] hover:bg-[rgba(0,0,0,0.02)]'
                }`}
              >
                {item}
              </button>
            )
          )}

          <button
            type="button"
            onClick={() => goToPage(currentPage + 1)}
            disabled={currentPage >= totalPages || loading}
            aria-label="Next page"
            className="inline-flex items-center gap-1 px-3 py-2 text-[12px] font-semibold text-[rgba(0,0,0,0.65)] bg-white border border-[rgba(0,0,0,0.12)] rounded-lg hover:bg-[rgba(0,0,0,0.02)] transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Next
            <Icon icon="mdi:chevron-right" width={14} />
          </button>
        </nav>
      )}

      {/* Scroll-to-top button */}
      {showScrollTop && (
        <button
          type="button"
          onClick={scrollToTop}
          aria-label="Scroll to top"
          title="Scroll to top"
          className="fixed bottom-6 right-6 z-40 w-11 h-11 flex items-center justify-center rounded-full bg-indigo-600 text-white shadow-lg hover:bg-indigo-700 transition-colors"
        >
          <Icon icon="mdi:arrow-up" width={20} />
        </button>
      )}
    </>
  );
};

export default ScriptGrid;
