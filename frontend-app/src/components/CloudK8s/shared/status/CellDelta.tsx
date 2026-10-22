/**
 * CellDelta (FEAT-001): a generic signed delta for a table cell — a thin arrow and a formatted
 * amount, tabular-nums so columns line up. Distinct from money `Delta.tsx`: Delta frames a money
 * saving / extra in the money tokens, while CellDelta is the neutral per-cell "+12 / -3" that a data
 * table shows in chrome ink. The arrow is ALWAYS present, so the sign is never colour alone; by
 * default there is no meaning colour at all (chrome ink), and an optional `tone` can tint it when a
 * cell's delta does carry meaning.
 */
import { Icon } from '@iconify/react';
import { toneClasses } from './toneClasses';
import type { Tone } from './statusTones';

export interface CellDeltaProps {
  /** The signed value; its sign picks the arrow. */
  value: number;
  /** The already-formatted magnitude, e.g. "12", "3.4 GiB", "8%". Defaults to the rounded number. */
  formatted?: string;
  /** Optional meaning tint; omitted leaves the delta in chrome ink. */
  tone?: Tone;
  className?: string;
}

export function CellDelta({ value, formatted, tone, className = '' }: CellDeltaProps) {
  const up = value > 0;
  const flat = value === 0;
  const text = formatted ?? String(Math.abs(Math.round(value * 10) / 10));
  const toneClass = tone ? toneClasses(tone).text : 'text-[color:var(--chrome-ink)]';

  return (
    <span
      className={`inline-flex items-center gap-0.5 text-[13px] font-semibold tabular-nums ${toneClass} ${className}`.trim()}
      data-cell-delta={flat ? 'flat' : up ? 'up' : 'down'}
      data-k8s-kit=""
    >
      <Icon
        icon={flat ? 'mdi:minus-thick' : up ? 'mdi:arrow-up-thin' : 'mdi:arrow-down-thin'}
        width={13}
        aria-hidden="true"
        className="shrink-0"
      />
      <span>{text}</span>
    </span>
  );
}

export default CellDelta;
