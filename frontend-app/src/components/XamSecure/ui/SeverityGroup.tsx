import type { IssueSummary } from '../../../types/xamsecure';
import type { SevKey } from './tokens';
import SeverityLetterBadge from './SeverityLetterBadge';
import Group, { MAX_OPEN_HEIGHT, openHeightFor } from './Group';

/** Re-exported from Group so existing imports (`import SeverityGroup, { openHeightFor }`) keep working. */
export { MAX_OPEN_HEIGHT, openHeightFor };

interface Props {
  severity: SevKey;
  label: string;
  issues: IssueSummary[];
  open: boolean;
  onToggle: () => void;
  onOpenIssue: (issue: IssueSummary) => void;
}

/**
 * A collapsible severity group (spec §5.2). A thin adapter over the generic `Group` primitive:
 * it supplies the severity letter badge as the header badge and keeps the group's public props
 * and DOM contract (bar button, chevron transform, pill, always-mounted FindingsTable) byte-
 * compatible with the original — the dedicated SeverityGroup tests are the guard.
 */
const SeverityGroup: React.FC<Props> = ({ severity, label, issues, open, onToggle, onOpenIssue }) => (
  <Group
    headerBadge={<SeverityLetterBadge severity={severity} size={22} />}
    label={label}
    count={issues.length}
    issues={issues}
    open={open}
    onToggle={onToggle}
    onOpenIssue={onOpenIssue}
  />
);

export default SeverityGroup;
