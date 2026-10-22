/**
 * CapacityBar (FEAT-001): a horizontal capacity track — the bar CapacityDonut's ring becomes when a
 * row only has the width for one line (docs/cloudk8s/ui-revamp-design.md §10). The full track is
 * allocatable; a `bg-series-requested` fill shows what pods reserve, a `bg-series-used` overlay shows
 * what they actually use, and a thin tick marks the overhead boundary. The three figures sit beside
 * the bar with the glossary's CAPACITY_TERMS tooltips, verbatim.
 *
 * Never fabricates: a missing datum is the muted em-dash with its tooltip, never a 0 or a made-up
 * value (CLAUDE.md). Colours are the requested / used series tokens, exactly like RequestBar; the
 * bar itself is decorative and the labels carry the numbers.
 */
import { DASH } from '../../../utils/k8sQuantities';
import { CAPACITY_TERMS } from './CapacityDonut';

export interface CapacityBarProps {
  /** "CPU" / "Memory": names the bar for assistive tech. */
  resource: string;
  /** The track total; the bar hides when null (nothing to scale against). */
  allocatable: number | null;
  requested: number | null;
  used: number | null;
  /** Overhead (provisioned − allocatable); draws the boundary tick when known. */
  overhead?: number | null;
  /** Formats a value of this resource: "3.92 cores", "2.1 GiB". */
  format: (value: number | null) => string;
  /** Why a datum is "—" (e.g. the source that didn't report `used`). */
  missingReason?: string;
  className?: string;
}

const pct = (part: number | null, whole: number | null) =>
  part !== null && whole !== null && whole > 0 ? Math.min(100, Math.max(0, (part / whole) * 100)) : null;

function Figure({ term, title, value, missing, missingReason }: {
  term: string; title: string; value: string; missing: boolean; missingReason?: string;
}) {
  return (
    <span className="flex items-baseline gap-1.5">
      <span className="text-[11px] font-semibold uppercase tracking-[0.04em] text-[color:var(--chrome-muted)]" title={title}>{term}</span>
      <span
        className="text-[13px] font-semibold tabular-nums text-[color:var(--chrome-ink)]"
        title={missing ? missingReason : undefined}
      >
        {value}
      </span>
    </span>
  );
}

export function CapacityBar({
  resource, allocatable, requested, used, overhead = null, format, missingReason, className = '',
}: CapacityBarProps) {
  const requestedPct = pct(requested, allocatable);
  const usedPct = pct(used, allocatable);
  // Overhead sits past allocatable on the provisioned total; its tick is at allocatable / (alloc + overhead).
  const overheadTickPct = overhead !== null && allocatable !== null && allocatable + overhead > 0
    ? (allocatable / (allocatable + overhead)) * 100
    : null;

  return (
    <div className={`flex min-w-[160px] flex-col gap-1.5 ${className}`.trim()} data-capacity-bar={resource}>
      {allocatable !== null ? (
        <span className="relative h-2 overflow-hidden rounded-full bg-series-allocatable" aria-hidden="true">
          {requestedPct !== null && (
            <span className="absolute inset-y-0 left-0 rounded-full bg-series-requested" style={{ width: `${requestedPct}%` }} />
          )}
          {usedPct !== null && (
            <span className="absolute inset-y-0 left-0 rounded-full bg-series-used" style={{ width: `${usedPct}%` }} />
          )}
          {overheadTickPct !== null && overheadTickPct < 100 && (
            <span className="absolute inset-y-0 w-px bg-series-overhead" style={{ left: `${overheadTickPct}%` }} />
          )}
        </span>
      ) : (
        <span className="text-[12px] text-[color:var(--chrome-muted)]" title={missingReason}>{DASH}</span>
      )}
      <span className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
        <Figure
          term="Requested" title={CAPACITY_TERMS.requested}
          value={requested === null ? DASH : format(requested)} missing={requested === null} missingReason={missingReason}
        />
        <Figure
          term="Used" title={CAPACITY_TERMS.used}
          value={used === null ? DASH : format(used)} missing={used === null} missingReason={missingReason}
        />
        <Figure
          term="Allocatable" title={CAPACITY_TERMS.allocatable}
          value={allocatable === null ? DASH : format(allocatable)} missing={allocatable === null} missingReason={missingReason}
        />
      </span>
    </div>
  );
}

export default CapacityBar;
