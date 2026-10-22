import React from 'react';
import { SEVERITY, PANEL } from './tokens';
import { SEVERITY_ORDER } from '../../../types/xamsecure';

interface Props {
  /** Count per severity level, keyed by the SEVERITY_ORDER levels. */
  counts: Record<string, number>;
}

/**
 * A single compact horizontal bar-strip showing each severity's share of the total, in
 * SEVERITY_ORDER. The empty track reads against both the white page and the #F7F8FA panel; each
 * visible segment is colored by its severity dot color at ~0.9 opacity. Zero-width segments are
 * omitted. When the total is zero the empty track renders alone.
 *
 * Placed ONCE on the page (the Security-posture section). Every segment pairs with the dot+label
 * legend above it, so meaning is never conveyed by color alone; an aria-label summarizes the
 * distribution for screen readers.
 */
const SeverityBarStrip: React.FC<Props> = ({ counts }) => {
  const total = SEVERITY_ORDER.reduce((a, sev) => a + (counts[sev] ?? 0), 0);

  const summary = total === 0
    ? 'No findings.'
    : SEVERITY_ORDER.map((sev) => `${counts[sev] ?? 0} ${sev.toLowerCase()}`).join(', ');

  return (
    <div
      className="w-full h-2 rounded-full overflow-hidden flex"
      style={{ background: PANEL.track }}
      role="img"
      aria-label={`Severity distribution: ${summary}`}
      title={summary}
    >
      {total > 0 && SEVERITY_ORDER.map((sev) => {
        const count = counts[sev] ?? 0;
        if (count <= 0) return null;
        const pct = (count / total) * 100;
        const palette = SEVERITY[sev] ?? SEVERITY.UNKNOWN;
        return (
          <span
            key={sev}
            className="h-full"
            style={{ width: `${pct}%`, background: palette.dot, opacity: 0.9 }}
          />
        );
      })}
    </div>
  );
};

export default SeverityBarStrip;
