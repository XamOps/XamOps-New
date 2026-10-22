/**
 * Trend (FEAT-001): the small "vs. a prior value" chip beside a hero figure (cast.ai's key detail).
 * It compares a current figure with a prior one and shows a thin arrow and the percent change,
 * toned by meaning. It renders NOTHING when `prior == null` — there is no made-up prior, so a hero
 * with no history shows no trend (never a fabricated 0%).
 *
 * "Toned by meaning" needs the caller to say which way is good: `goodDirection` (default 'up').
 * A move in the good direction is `status-good`, the opposite is `status-warn`, and no change is the
 * muted `status-off`. The arrow (mdi:arrow-up-thin / -down-thin) is always present, so the direction
 * is never carried by colour alone.
 */
import { Icon } from '@iconify/react';
import { toneClasses } from './toneClasses';
import type { Tone } from './statusTones';

export interface TrendProps {
  current: number;
  /** The value to compare against. `null` means no history — the chip renders nothing. */
  prior: number | null;
  /** Which direction is "good" (default 'up'): decides good vs. warn toning. */
  goodDirection?: 'up' | 'down';
  className?: string;
}

/** Round to one decimal, dropping a trailing ".0". */
function formatPct(value: number): string {
  const rounded = Math.round(value * 10) / 10;
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(1);
}

export function Trend({ current, prior, goodDirection = 'up', className = '' }: TrendProps) {
  if (prior === null || !Number.isFinite(prior) || !Number.isFinite(current)) return null;

  const delta = current - prior;
  const pct = prior !== 0 ? (delta / Math.abs(prior)) * 100 : (delta === 0 ? 0 : null);
  const up = delta > 0;
  const flat = delta === 0;

  const tone: Tone = flat ? 'off' : (up === (goodDirection === 'up') ? 'good' : 'warn');
  const t = toneClasses(tone);

  return (
    <span
      className={`inline-flex items-center gap-0.5 text-[12px] font-semibold tabular-nums ${t.text} ${className}`.trim()}
      data-trend={flat ? 'flat' : up ? 'up' : 'down'}
      data-k8s-kit=""
    >
      <Icon
        icon={flat ? 'mdi:minus-thick' : up ? 'mdi:arrow-up-thin' : 'mdi:arrow-down-thin'}
        width={14}
        aria-hidden="true"
        className="shrink-0"
      />
      <span>{pct === null ? '—' : `${formatPct(Math.abs(pct))}%`}</span>
    </span>
  );
}

export default Trend;
