import React, { useEffect, useState } from 'react';
import type { DspmAssetDetail, DspmEntityCount, DspmObject } from '../../types/dspm';
import { categoryAccent, compactCount } from './dspmFormat';
import Pager from './DspmPager';

/**
 * Pieces shared by the Cloud asset page and the SaaS asset drawer, so the two cannot drift on how an
 * entity is counted or how a file list pages.
 */

// ── entities ──────────────────────────────────────────────────────────────────────────────────

/** Entities grouped by category, in first-seen order. Flattened, this is also the table order. */
export function groupEntities(entities: DspmEntityCount[]): Array<[string, DspmEntityCount[]]> {
  const groups = new Map<string, DspmEntityCount[]>();
  for (const entity of entities) {
    const key = entity.categoryLabel ?? 'Other';
    groups.set(key, [...(groups.get(key) ?? []), entity]);
  }
  return [...groups.entries()];
}

/** Entities in display order: grouped by category, highest count first within each. */
export const orderedEntities = (entities: DspmEntityCount[]) =>
  groupEntities(entities).flatMap(([, list]) => list);

/**
 * Count text. The API returns at most 50 objects per asset, so on a larger asset a count summed from
 * them is a minimum ("+"), and an entity reported for the asset but absent from those objects has no
 * count at all ("—"), never 0.
 */
export function entityCountText(entity: DspmEntityCount, partial: boolean): string {
  if (!entity.sampled) return '—';
  return `${compactCount(entity.count)}${partial ? '+' : ''}`;
}

export function entityCountTitle(entity: DspmEntityCount, detail: DspmAssetDetail): string {
  if (!entity.sampled) {
    return `Detected in this asset, but not in the ${detail.objects.length} files the API returns, so it cannot be counted`;
  }
  return detail.breakdownPartial
    ? `At least ${entity.count.toLocaleString()} — counted across ${detail.objects.length} of ${detail.objectTotal.toLocaleString()} files`
    : entity.count.toLocaleString();
}

/** Every finding of one entity in one object, summed — an object can report the same detector twice. */
export const countIn = (object: DspmObject, entityType: string) =>
  object.findings.filter(f => f.entityType === entityType).reduce((sum, f) => sum + f.count, 0);

/** The objects containing an entity, most records first. */
export const filesWith = (objects: DspmObject[], entityType: string) =>
  objects
    .filter(object => object.findings.some(f => f.entityType === entityType))
    .map(object => ({ object, count: countIn(object, entityType) }))
    .sort((a, b) => b.count - a.count);

export const CategoryChip: React.FC<{ label: string | null }> = ({ label }) => {
  if (!label) return null;
  const accent = categoryAccent(label);
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

export const PagedFooter: React.FC<{
  page: number; totalPages: number; size: number; total: number;
  onPage: (page: number) => void; onSize: (size: number) => void;
  /** Show even when everything fits on one page, with "Showing 1 - 3 of 3", as the SaaS tables do. */
  always?: boolean;
  className?: string;
}> = ({ page, totalPages, size, total, onPage, onSize, always, className }) => {
  if (total === 0 || (!always && total <= FILE_PAGE_SIZES[0])) return null;
  const from = (page - 1) * size + 1;
  const to = Math.min(page * size, total);
  return (
    <div className={`flex flex-wrap items-center justify-between gap-3 text-[11px] text-[rgba(0,0,0,0.5)] ${className ?? 'mt-3 rounded-lg border border-[rgba(0,0,0,0.08)] bg-white px-3 py-2'}`}>
      <label className="flex items-center gap-1.5">
        <span className="text-[rgba(0,0,0,0.4)]">Rows Per Page</span>
        <select
          value={size}
          onChange={e => onSize(Number(e.target.value))}
          className="rounded-lg border border-[rgba(0,0,0,0.08)] bg-[rgba(0,0,0,0.03)] px-1.5 py-1 text-[11px] focus:outline-none focus:ring-1 focus:ring-[rgba(0,0,0,0.2)]"
        >
          {FILE_PAGE_SIZES.map(s => <option key={s} value={s}>{s}</option>)}
        </select>
      </label>
      <div className="flex items-center gap-3">
        <span>Showing {from} - {to} of {total.toLocaleString()}</span>
        <Pager page={page} totalPages={totalPages} onPage={onPage} />
      </div>
    </div>
  );
};
