import React, { useState, useMemo } from 'react';

/** Client-side pagination over an already-loaded array. */
export function usePaged<T>(items: T[], pageSize: number) {
  const [page, setPage] = useState(0);
  const totalPages = Math.max(1, Math.ceil(items.length / pageSize));
  // Clamp when the list shrinks (e.g., filter change).
  const safePage = Math.min(page, totalPages - 1);
  const pageItems = useMemo(
    () => items.slice(safePage * pageSize, safePage * pageSize + pageSize),
    [items, safePage, pageSize]
  );
  return { page: safePage, setPage, pageItems, totalPages, total: items.length };
}

interface PagerProps {
  page: number;          // 0-indexed
  totalPages: number;
  total: number;
  unit?: string;
  onPage: (page: number) => void;
}

/** Prev / Next control matching the XamSecure table footers. */
const Pager: React.FC<PagerProps> = ({ page, totalPages, total, unit = 'items', onPage }) => {
  if (totalPages <= 1) return null;
  return (
    <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
      <span>Page {page + 1} of {totalPages} · {total.toLocaleString()} {unit}</span>
      <div className="flex gap-2">
        <button disabled={page === 0} onClick={() => onPage(page - 1)}
                className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
        <button disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}
                className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
      </div>
    </div>
  );
};

export default Pager;
