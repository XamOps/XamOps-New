import React, { useEffect, useState } from 'react';
import { categoryAccent } from './dspmFormat';

/**
 * Pieces the Datastores page and its drawers share: the category chip and client-side paging.
 */

/** A category's chip in its accent colour, or grey with `neutral` (a document category, not a sensitive one). */
export const CategoryChip: React.FC<{ label: string | null; neutral?: boolean }> = ({ label, neutral }) => {
  if (!label) return null;
  const accent = categoryAccent(neutral ? null : label);
  return (
    <span className={`inline-flex items-center gap-1.5 whitespace-nowrap rounded border px-1.5 py-0.5 text-[10px] font-semibold ${accent.chip}`}>
      <span className={`h-1.5 w-1.5 rounded-sm ${accent.bar}`} />
      {label}
    </span>
  );
};

// ── paging ────────────────────────────────────────────────────────────────────────────────────

/** 10 first, as the console's tables have it. */
export const FILE_PAGE_SIZES = [10, 25, 50] as const;

/**
 * Client-side paging over rows already loaded. The API returns at most 50 objects per asset with no
 * object-level paging, so there is nothing further to fetch; this only splits them up.
 *
 * `resetKey` returns to page 1 when the list changes underneath (another entity, a new search), since
 * staying on page 4 of a shorter list would show nothing.
 */
export function usePaged<T>(items: T[], resetKey: string) {
  const [page, setPage] = useState(1);
  const [size, setSize] = useState<number>(FILE_PAGE_SIZES[0]);
  useEffect(() => { setPage(1); }, [resetKey, size]);

  const totalPages = Math.max(1, Math.ceil(items.length / size));
  const current = Math.min(page, totalPages);
  return {
    items: items.slice((current - 1) * size, current * size),
    page: current, totalPages, size, total: items.length, setPage, setSize,
  };
}
