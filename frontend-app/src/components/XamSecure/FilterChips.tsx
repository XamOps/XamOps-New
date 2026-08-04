import React from 'react';

export interface ChipOption {
  /** Stable filter value; null is reserved for the "All" chip. */
  value: string;
  label: string;
  count: number;
}

interface Props {
  options: ChipOption[];
  active: string | null;
  onChange: (value: string | null) => void;
  /** Total behind the "All" chip. */
  allCount: number;
  allLabel?: string;
  /** Optional tint per value, used for severity so the chips read at a glance. */
  tint?: (value: string) => string | undefined;
}

/**
 * Group-by / filter chips with counts. Upwind exposes grouping on every findings list;
 * flat pagination alone makes distribution invisible.
 *
 * Counts belong on the chips so the breakdown is readable *before* filtering — that is
 * the grouping value, not just the filtering.
 */
const FilterChips: React.FC<Props> = ({ options, active, onChange, allCount, allLabel = 'All', tint }) => (
  <div className="flex flex-wrap items-center gap-1.5">
    <Chip label={allLabel} count={allCount} active={active === null} onClick={() => onChange(null)} />
    {options.map((o) => (
      <Chip
        key={o.value}
        label={o.label}
        count={o.count}
        active={active === o.value}
        dotColor={tint?.(o.value)}
        onClick={() => onChange(active === o.value ? null : o.value)}
      />
    ))}
  </div>
);

const Chip: React.FC<{
  label: string; count: number; active: boolean; onClick: () => void; dotColor?: string;
}> = ({ label, count, active, onClick, dotColor }) => (
  <button
    type="button"
    onClick={onClick}
    aria-pressed={active}
    className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-[11px] font-semibold border transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900 focus-visible:ring-offset-1 ${
      active
        ? 'bg-gray-900 border-gray-900 text-white'
        : 'bg-white border-gray-200 text-gray-600 hover:border-gray-400'}`}
  >
    {dotColor && (
      <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ backgroundColor: dotColor }} />
    )}
    {label}
    <span className={active ? 'text-gray-300' : 'text-gray-400'}>{count}</span>
  </button>
);

/** Shared severity tint for chips, matching SeverityChip's palette. */
export const SEVERITY_TINT: Record<string, string> = {
  CRITICAL: '#dc2626',
  HIGH: '#f97316',
  MEDIUM: '#eab308',
  LOW: '#10b981',
};

export default FilterChips;
