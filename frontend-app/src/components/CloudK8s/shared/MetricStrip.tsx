/**
 * MetricStrip (FEAT-001): the hero strip cast.ai leads a page with — one Surface split into cells by
 * a single hairline, each cell a LABEL caption over one FIGURE, with an optional Trend chip and an
 * optional SECONDARY sub-line. Every cell shares the one figure size (FIGURE); hierarchy is
 * placement, not a bigger number per cell.
 *
 * Presentational: the caller passes the already-formatted figure and, for a Trend, a `{current,
 * prior}` pair (a cell with `prior == null` simply shows no trend — never a fabricated one). A
 * `loading` strip renders a matching skeleton with the same cell count.
 */
import type { ReactNode } from 'react';
import { Surface } from './Surface';
import { Trend } from './status/Trend';
import { FIGURE, LABEL, SECONDARY } from './typeScale';

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
  loading?: boolean;
  'aria-label'?: string;
  className?: string;
}

function Cell({ cell }: { cell: MetricCell }) {
  return (
    <div className="flex min-w-0 flex-col gap-1 px-5 py-4 first:pl-0 last:pr-0">
      <span className={LABEL}>{cell.label}</span>
      <div className="flex items-baseline gap-2">
        <span className={FIGURE}>{cell.value}</span>
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
      <span className="h-2.5 w-16 rounded bg-[rgba(0,0,0,0.06)] dark:bg-[rgba(255,255,255,0.08)]" />
      <span className="h-7 w-24 rounded bg-[rgba(0,0,0,0.06)] dark:bg-[rgba(255,255,255,0.08)]" />
    </div>
  );
}

export function MetricStrip({ cells, loading = false, 'aria-label': ariaLabel, className = '' }: MetricStripProps) {
  return (
    <Surface tier="hero" aria-label={ariaLabel} className={className}>
      <div
        className="flex flex-wrap items-stretch divide-x divide-[color:var(--chrome-line)]"
        aria-busy={loading || undefined}
      >
        {loading
          ? cells.map((_, i) => <SkeletonCell key={i} />)
          : cells.map((cell, i) => <Cell key={`${cell.label}-${i}`} cell={cell} />)}
      </div>
    </Surface>
  );
}

export default MetricStrip;
