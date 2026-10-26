import { useState } from 'react';
import type { IssueSummary } from '../../../types/xamsecure';
import SeverityLetterBadge from './SeverityLetterBadge';
import { ResourcesCell, DateCell, FrameworkCell } from './IssueCells';

/**
 * Shared column template for the findings header + rows so the two align exactly.
 * Finding is the only flexible column (`minmax(0,1fr)` so its title can ellipsize).
 */
export const GRID_TEMPLATE = 'minmax(0,1fr) 96px 140px 140px 160px';

/** Height of a single finding row in px (drives the collapse cap math in SeverityGroup). */
export const ROW_HEIGHT = 56;

interface Props {
  issue: IssueSummary;
  onOpen: (issue: IssueSummary) => void;
}

/**
 * A finding row (spec §5.4). A real native `<button>` laid out as a grid so Enter/Space
 * activate for free and it carries a meaningful accessible name — no `role="row"`, no
 * custom key handler. Cells are decorative children (no `role="cell"`).
 */
const FindingRow: React.FC<Props> = ({ issue, onOpen }) => {
  const [hover, setHover] = useState(false);
  return (
    <button
      type="button"
      aria-label={`${issue.title}, severity ${issue.severity}`}
      onClick={() => onOpen(issue)}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      style={{
        // reset native button chrome
        appearance: 'none',
        background: hover ? 'var(--xs-interactive-hover)' : 'transparent',
        border: 0,
        margin: 0,
        font: 'inherit',
        textAlign: 'left',
        width: '100%',
        cursor: 'pointer',
        transition: 'background-color .1s ease-in-out',
        // grid layout
        display: 'grid',
        gridTemplateColumns: GRID_TEMPLATE,
        alignItems: 'center',
        height: ROW_HEIGHT,
        padding: '0 8px',
        columnGap: 8,
        boxSizing: 'border-box',
      }}
    >
      {/* Finding */}
      <span style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
        <SeverityLetterBadge severity={issue.severity} size={22} />
        <span
          style={{
            fontSize: 14,
            lineHeight: '21px',
            fontWeight: 400,
            color: 'var(--xs-text-primary)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
            minWidth: 0,
          }}
        >
          {issue.title}
        </span>
      </span>
      {/* Resources */}
      <ResourcesCell count={issue.resourceCount} />
      {/* First seen */}
      <DateCell iso={issue.firstSeen} />
      {/* Last evaluation */}
      <DateCell iso={issue.lastEvaluation} />
      {/* Framework */}
      <FrameworkCell frameworks={issue.frameworks} />
    </button>
  );
};

export default FindingRow;
