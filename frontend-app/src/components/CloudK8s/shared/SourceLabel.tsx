/**
 * "Source · freshness", with the absolute time on hover and for assistive tech
 * (docs/cloudk8s/ui-revamp-design.md §1, §14). Every number names where it came from and how fresh it is.
 */

/** "40 s ago", "4 min ago", "3 h ago", "2 d ago"; null for a missing or unparseable time. */
export function formatAgo(iso: string | null | undefined, now: number = Date.now()): string | null {
  if (!iso) return null;
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return null;
  const seconds = Math.max(0, Math.round((now - at) / 1000));
  if (seconds < 60) return `${seconds} s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.floor(hours / 24)} d ago`;
}

/** The absolute time in the viewer's locale, with the zone, e.g. "10 Oct 2026, 15:00 IST". */
export function formatAbsolute(iso: string): string {
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return iso;
  return at.toLocaleString(undefined, {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', timeZoneName: 'short',
  });
}

export interface SourceLabelProps {
  /** Who reported it, e.g. "XamOps agent". */
  source: string;
  /** ISO-8601 time of the data, or null when the source reported none. */
  at: string | null | undefined;
  /** Word before the age, e.g. "data", "seen", "synced". */
  verb?: string;
  /** Shown instead of an age when `at` is missing. */
  missingText?: string;
  now?: number;
  className?: string;
}

export function SourceLabel({ source, at, verb, missingText = 'not reported', now, className = '' }: SourceLabelProps) {
  const ago = formatAgo(at, now);
  return (
    <span className={`inline-flex items-center gap-1 text-[12px] text-[rgba(0,0,0,0.6)] ${className}`}>
      <span className="font-semibold text-[rgba(0,0,0,0.88)]">{source}</span>
      <span aria-hidden="true">·</span>
      {ago && at ? (
        <time dateTime={at} title={formatAbsolute(at)}>
          {verb ? `${verb} ${ago}` : ago}
          <span className="sr-only"> ({formatAbsolute(at)})</span>
        </time>
      ) : (
        <span>{missingText}</span>
      )}
    </span>
  );
}

export default SourceLabel;
