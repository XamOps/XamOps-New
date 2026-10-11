/**
 * A KPI tile with a null-aware value and its source (docs/cloudk8s/ui-revamp-design.md §10.1, D10, §14).
 *
 * A value nobody reported is `null`: the tile shows "—" and "Not reported by <source>", never 0.
 * A real 0 is a value and shows as 0.
 */
import type { ReactNode } from 'react';
import { DASH } from '../../../utils/k8sQuantities';

export interface MetricTileProps {
  label: string;
  value: number | null | undefined;
  /** Formats a known value; defaults to the number as is. */
  format?: (value: number) => string;
  /** After the value, e.g. "/ 6". Hidden when the value is unknown. */
  suffix?: ReactNode;
  /** A line under the value, e.g. "2 pending · 0 failed". Hidden when the value is unknown. */
  detail?: ReactNode;
  /** Who reported the value, e.g. "XamOps agent · k8s-resources". */
  source: string;
  /** Replaces "Not reported by <source>" when the value is unknown. */
  missingText?: string;
  /** Tooltip on the value, e.g. how an Estimated figure was computed. */
  title?: string;
  className?: string;
}

export function MetricTile({ label, value, format, suffix, detail, source, missingText, title, className = '' }: MetricTileProps) {
  const known = typeof value === 'number' && Number.isFinite(value);
  return (
    <div
      role="group"
      aria-label={label}
      data-known={known}
      className={`flex min-w-0 flex-col gap-1 rounded-lg border border-[rgba(0,0,0,0.12)] bg-white px-4 py-3 ${className}`}
    >
      <span className="text-[12px] font-medium text-[rgba(0,0,0,0.6)]">{label}</span>
      <span className="flex items-baseline gap-1 text-[24px] font-semibold leading-tight tabular-nums text-[rgba(0,0,0,0.88)]" title={title}>
        {known ? (format ? format(value) : String(value)) : DASH}
        {known && suffix && <span className="text-[14px] font-medium text-[rgba(0,0,0,0.6)]">{suffix}</span>}
      </span>
      {known
        ? detail && <span className="text-[12px] text-[rgba(0,0,0,0.6)]">{detail}</span>
        : <span className="text-[12px] text-[rgba(0,0,0,0.6)]">{missingText ?? `Not reported by ${source}`}</span>}
      <span className="mt-auto pt-1 text-[12px] text-[rgba(0,0,0,0.6)]" data-source>{source}</span>
    </div>
  );
}

export default MetricTile;
