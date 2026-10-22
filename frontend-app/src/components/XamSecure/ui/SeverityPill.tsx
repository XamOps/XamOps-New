import React from 'react';
import { SEVERITY } from './tokens';

/**
 * A small rounded count pill tinted by severity. Page-local to the Overview re-skin.
 *
 * `severity` is `string | null` (matching `SeverityChip`); null/unrecognized falls back to the
 * UNKNOWN palette. The pill carries a numeric count; the accompanying `SeverityDot` carries the
 * text label, so severity is never conveyed by color alone.
 */
const SeverityPill: React.FC<{ severity: string | null; count: number; className?: string }> = ({
  severity, count, className,
}) => {
  const sev = (severity ?? 'UNKNOWN').toUpperCase();
  const palette = SEVERITY[sev] ?? SEVERITY.UNKNOWN;
  return (
    <span
      className={`inline-flex items-center justify-center rounded-full px-2 py-0.5 text-[11px] font-semibold font-mono tabular-nums ${className ?? ''}`}
      style={{ background: palette.pillBg, color: palette.pillText }}
    >
      {count.toLocaleString()}
    </span>
  );
};

export default SeverityPill;
