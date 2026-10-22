/**
 * MetricStrip (FEAT-001): the hero strip cast.ai leads a page with — one Surface split into cells by
 * a single hairline, each cell a LABEL caption over one figure, with an optional Trend chip and an
 * optional SECONDARY sub-line. With `heroLead`, the lead cell takes the dominant HERO_FIGURE (28px)
 * and the rest take the subordinate FIGURE (20px), so the hero out-weighs its neighbours (owner
 * review); without it (Pods' phase counts), every cell is an equal FIGURE peer.
 *
 * Presentational: the caller passes the already-formatted figure and, for a Trend, a `{current,
 * prior}` pair (a cell with `prior == null` simply shows no trend — never a fabricated one). A
 * `loading` strip renders a matching skeleton with the same cell count.
 */
import type { ReactNode } from 'react';
import { Surface } from './Surface';
import { Trend } from './status/Trend';
import { FIGURE, HERO_FIGURE, LABEL, SECONDARY } from './typeScale';

export interface MetricCell {
  /** The caption above the figure, e.g. "Monthly cost". */
  label: string;
  /** The already-formatted figure, e.g. "$1,240". */
  value: ReactNode;
  /** Optional trend vs. a prior value; renders nothing when `prior == null`. */
  trend?: { current: number; prior: number | null; goodDirection?: 'up' | 'down' };
  /** Optional one-line sub-caption under the figure. */
  sub?: ReactNode;
}

export interface MetricStripProps {
  cells: readonly MetricCell[];
  /**
   * Give the lead cell (the first) true hero emphasis: a larger figure (HERO_FIGURE) than the strip
   * cells (FIGURE), so hierarchy isn't placement alone. Off by default (Pods' phase counts are
   * peers); Overview turns it on so Monthly cost dominates.
   */
  heroLead?: boolean;
  loading?: boolean;
  'aria-label'?: string;
  className?: string;
}

function Cell({ cell, hero = false }: { cell: MetricCell; hero?: boolean }) {
  return (
    // A hero lead cell never shrinks (so it keeps its weight); the rest may shrink but never below
    // their content, keeping the whole strip on ONE horizontal row (the parent scrolls if tight).
    <div className={`flex flex-col gap-1 px-5 py-4 first:pl-0 last:pr-0 ${hero ? 'shrink-0' : 'min-w-0'}`.trim()} data-metric-cell={hero ? 'hero' : 'strip'}>
      <span className={LABEL}>{cell.label}</span>
      <div className="flex items-baseline gap-2">
        <span className={hero ? HERO_FIGURE : FIGURE}>{cell.value}</span>
        {cell.trend && (
          <Trend current={cell.trend.current} prior={cell.trend.prior} goodDirection={cell.trend.goodDirection} />
        )}
      </div>
      {cell.sub !== undefined && cell.sub !== null && <span className={SECONDARY}>{cell.sub}</span>}
    </div>
  );
}

function SkeletonCell() {
  return (
    <div className="flex min-w-0 flex-col gap-2 px-5 py-4 first:pl-0 last:pr-0" aria-hidden="true">
      <span className="h-2.5 w-16 rounded-[6px] bg-[rgba(0,0,0,0.06)] dark:bg-[rgba(255,255,255,0.08)]" />
      <span className="h-7 w-24 rounded-[6px] bg-[rgba(0,0,0,0.06)] dark:bg-[rgba(255,255,255,0.08)]" />
    </div>
  );
}

export function MetricStrip({ cells, heroLead = false, loading = false, 'aria-label': ariaLabel, className = '' }: MetricStripProps) {
  return (
    <Surface tier="hero" aria-label={ariaLabel} className={className}>
      {/*
        ONE horizontal row, never a wrapped vertical stack: the cells sit on a single flex row split
        by one hairline. When the column is too narrow for every cell, the row scrolls horizontally
        inside the strip (overflow-x-auto) rather than wrapping each cell onto its own line.
      */}
      <div className="overflow-x-auto">
        <div
          className="flex items-stretch divide-x divide-[color:var(--chrome-line)]"
          aria-busy={loading || undefined}
          data-metric-strip-row=""
        >
          {loading
            ? cells.map((_, i) => <SkeletonCell key={i} />)
            : cells.map((cell, i) => <Cell key={`${cell.label}-${i}`} cell={cell} hero={heroLead && i === 0} />)}
        </div>
      </div>
    </Surface>
  );
}

export default MetricStrip;
