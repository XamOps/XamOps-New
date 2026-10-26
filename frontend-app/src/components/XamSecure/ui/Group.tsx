import { useId } from 'react';
import { Icon } from '@iconify/react';
import type { IssueSummary } from '../../../types/xamsecure';
import FindingsCountPill from './FindingsCountPill';
import FindingsTable, { HEADER_HEIGHT } from './FindingsTable';
import { ROW_HEIGHT } from './FindingRow';

/** Max visible height of an expanded group (spec §5.3/§7.1). */
export const MAX_OPEN_HEIGHT = 499;

/** Open height for a group of `rows` findings: header + rows, clamped to the cap. */
export function openHeightFor(rows: number): number {
  return Math.min(HEADER_HEIGHT + rows * ROW_HEIGHT, MAX_OPEN_HEIGHT);
}

interface Props {
  /** Leading badge node shown in the group bar (severity letter badge / framework logo). */
  headerBadge: React.ReactNode;
  label: string;
  /** Count shown in the "N Findings" pill (usually `issues.length`). */
  count: number;
  issues: IssueSummary[];
  open: boolean;
  onToggle: () => void;
  onOpenIssue: (issue: IssueSummary) => void;
}

/**
 * A generic collapsible group (spec §5.2). The bar is a semantic toggle button; the collapse
 * container height-animates 0 ↔ openHeight. The FindingsTable + all rows are ALWAYS MOUNTED
 * (never `{open && …}`); only `height` and `visibility` toggle, so every finding stays in the
 * DOM and screen-reader reachable while collapsed, yet collapsed rows leave the tab order.
 *
 * Owns the collapse/height/visibility mechanics and the openHeightFor math so both SeverityGroup
 * and FrameworkGroup render identically — only their `headerBadge`/`label` differ.
 */
const Group: React.FC<Props> = ({ headerBadge, label, count, issues, open, onToggle, onOpenIssue }) => {
  const collapseId = useId();
  const height = open ? openHeightFor(issues.length) : 0;

  return (
    <div style={{ padding: '8px 0' }}>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={collapseId}
        onClick={onToggle}
        style={{
          appearance: 'none',
          background: 'var(--xs-bg-primary)',
          border: 0,
          margin: 0,
          font: 'inherit',
          textAlign: 'left',
          width: '100%',
          height: 40,
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          cursor: 'pointer',
          padding: 0,
          marginBottom: open ? 12.5 : 0,
          boxSizing: 'border-box',
        }}
      >
        <Icon
          icon="mdi:chevron-down"
          width={16}
          height={16}
          aria-hidden
          style={{
            color: 'var(--xs-text-primary)',
            flexShrink: 0,
            transform: open ? 'rotate(0deg)' : 'rotate(-90deg)',
            transition: 'transform .35s ease-in-out',
          }}
        />
        {headerBadge}
        <span style={{ fontSize: 14, lineHeight: '21px', fontWeight: 400, color: 'var(--xs-text-primary)' }}>
          {label}
        </span>
        <FindingsCountPill count={count} />
      </button>

      <div
        id={collapseId}
        className="xs-collapse"
        style={{ height, visibility: open ? 'visible' : 'hidden' }}
      >
        <FindingsTable issues={issues} onOpenIssue={onOpenIssue} />
      </div>
    </div>
  );
};

export default Group;
