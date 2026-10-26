import type { IssueSummary } from '../../../types/xamsecure';
import { FrameworkLogo } from '../../../utils/frameworkBranding';
import Group from './Group';

/** Sentinel framework key for issues that list no framework. */
export const NO_FRAMEWORK_KEY = '__none__';
export const NO_FRAMEWORK_LABEL = 'No framework';

interface Props {
  /** Normalised framework key (via getFrameworkKey) or NO_FRAMEWORK_KEY. */
  frameworkKey: string;
  /** Human display name (via getFwConfig(key).displayName) or NO_FRAMEWORK_LABEL. */
  displayName: string;
  issues: IssueSummary[];
  open: boolean;
  onToggle: () => void;
  onOpenIssue: (issue: IssueSummary) => void;
}

/**
 * A framework-keyed collapsible group (By-framework grouping). Reuses the generic `Group`
 * primitive — identical collapse/always-mounted mechanics as SeverityGroup — but leads with the
 * framework logo as its header badge. The "No framework" bucket falls through to FrameworkLogo's
 * mdi icon fallback, a neutral badge (no fabricated brand).
 */
const FrameworkGroup: React.FC<Props> = ({ frameworkKey, displayName, issues, open, onToggle, onOpenIssue }) => (
  <Group
    headerBadge={<FrameworkLogo framework={frameworkKey} size={22} />}
    label={displayName}
    count={issues.length}
    issues={issues}
    open={open}
    onToggle={onToggle}
    onOpenIssue={onOpenIssue}
  />
);

export default FrameworkGroup;
