import React from 'react';
import { SEVERITY } from './tokens';

/**
 * A light severity marker: a small colored dot + an always-present uppercase text label.
 *
 * Page-local to the Overview re-skin (the shared `SeverityChip` is used by ~24 other files and is
 * intentionally left untouched). Severity is NEVER color-only — the label carries the meaning and
 * the dot is `aria-hidden`. `severity` is `string | null` to match `SeverityChip`'s signature,
 * because this renders both the non-null `IssueSummary.severity` and the nullable
 * `ThreatDetection`/`ThreatStory` severities; null/unrecognized falls back to UNKNOWN.
 */
const SeverityDot: React.FC<{ severity: string | null; className?: string }> = ({ severity, className }) => {
  const sev = (severity ?? 'UNKNOWN').toUpperCase();
  const palette = SEVERITY[sev] ?? SEVERITY.UNKNOWN;
  return (
    <span className={`inline-flex items-center gap-1.5 ${className ?? ''}`}>
      <span
        aria-hidden="true"
        className="inline-block rounded-full flex-shrink-0"
        style={{ width: 8, height: 8, background: palette.dot }}
      />
      <span className="text-[11px] font-semibold uppercase tracking-wide" style={{ color: palette.pillText }}>
        {sev}
      </span>
    </span>
  );
};

export default SeverityDot;
