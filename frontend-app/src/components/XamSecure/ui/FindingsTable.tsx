import type { IssueSummary } from '../../../types/xamsecure';
import FindingRow, { GRID_TEMPLATE } from './FindingRow';

/** Fixed header-block height in px = 40px cell + 1px bottom hairline (spec §5.3). */
export const HEADER_HEIGHT = 41;

interface Props {
  issues: IssueSummary[];
  onOpenIssue: (issue: IssueSummary) => void;
}

const COLUMNS = ['Finding', 'Resources', 'First seen', 'Last evaluation', 'Framework'];

/**
 * Fixed-height, internally-scrollable findings table (spec §5.3). The 41px header block is a
 * sibling OUTSIDE the scroll body so the cap math (`min(41 + rows*56, 499)`) stays exact and no
 * sticky-header paint bugs appear under the hidden scrollbar. Only the header carries grid
 * row/columnheader roles; the body is a plain stack of button rows (§5.4).
 */
const FindingsTable: React.FC<Props> = ({ issues, onOpenIssue }) => (
  <div style={{ paddingLeft: 14, display: 'flex', flexDirection: 'column', height: '100%' }}>
    {/* Header block — fixed 41px, outside the scroll body. */}
    <div
      role="row"
      style={{
        display: 'grid',
        gridTemplateColumns: GRID_TEMPLATE,
        alignItems: 'center',
        height: 40,
        padding: '0 8px',
        columnGap: 8,
        boxSizing: 'border-box',
        borderBottom: '1px solid var(--xs-border-subtle)',
        background: 'var(--xs-bg-primary)',
        flex: '0 0 auto',
      }}
    >
      {COLUMNS.map((label) => (
        <div
          key={label}
          role="columnheader"
          style={{
            fontSize: 14,
            lineHeight: '21px',
            fontWeight: 400,
            color: 'var(--xs-text-secondary)',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {label}
        </div>
      ))}
    </div>

    {/* Scroll body — all rows, invisible scrollbar, scroll stays functional. */}
    <div className="xs-invisible-scroll" style={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto' }}>
      {issues.map((issue) => (
        <FindingRow key={issue.check} issue={issue} onOpen={onOpenIssue} />
      ))}
    </div>
  </div>
);

export default FindingsTable;
