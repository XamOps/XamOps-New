import React, { useMemo, useState } from 'react';
import type { IssueSummary, IssueDetail, ResourceDetail } from '../../types/xamsecure';
import type { SevKey } from './ui/tokens';
import { getFrameworkKey, getFwConfig } from '../../utils/frameworkBranding';
import IssueDrawer from './IssueDrawer';
import { useResourceDrawer } from './useResourceDrawer';
import SeverityQuickFilter from './ui/SeverityQuickFilter';
import SeverityGroup from './ui/SeverityGroup';
import FrameworkGroup, { NO_FRAMEWORK_KEY, NO_FRAMEWORK_LABEL } from './ui/FrameworkGroup';
import IssuesToolbar from './ui/IssuesToolbar';
import IssuesSearch from './ui/IssuesSearch';
import ActiveFilters from './ui/ActiveFilters';
import GroupingTabs, { type Grouping } from './ui/GroupingTabs';
import './ui/tokens.css';

interface Props {
  data: IssueSummary[] | null;
  isLoading: boolean;
  loadDetail: (check: string) => Promise<IssueDetail>;
  loadResourceDetail?: (resourceId: string) => Promise<ResourceDetail>;
}

/** The four first-class severities, in display order (locally typed as SevKey — see plan). */
const SEV_GROUPS: SevKey[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
const SEV_LABEL: Record<SevKey, string> = { CRITICAL: 'Critical', HIGH: 'High', MEDIUM: 'Medium', LOW: 'Low' };

interface FrameworkBucket {
  key: string;
  displayName: string;
  issues: IssueSummary[];
}

/**
 * Issues view — the default XamSecure landing. Failing posture checks collapsed into one
 * actionable row each (grouped by check across all affected resources), then grouped into
 * collapsible groups by severity (default) or by compliance framework. An Upwind-style chrome
 * fills the top: a real title-search (filters by title), removable active-filter pills, and the
 * grouping switch. Each group reveals a fixed-height, internally-scrollable findings table;
 * clicking a finding opens the read-only detail drawer. Light-only, token-driven.
 */
const IssuesTab: React.FC<Props> = ({ data, isLoading, loadDetail, loadResourceDetail }) => {
  const [selected, setSelected] = useState<IssueSummary | null>(null);
  const [detail, setDetail] = useState<IssueDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const { open, drawer } = useResourceDrawer(loadResourceDetail);
  const [severity, setSeverity] = useState<string | null>(null);
  const [search, setSearch] = useState('');
  const [grouping, setGrouping] = useState<Grouping>('severity');
  const [openGroups, setOpenGroups] = useState<Set<SevKey>>(new Set());
  const [openFrameworks, setOpenFrameworks] = useState<Set<string>>(new Set());
  const unfiltered = data ?? [];

  const query = search.trim().toLowerCase();

  // Title search drives the SAME issue set through whichever grouping is active.
  const filtered = useMemo(
    () => (query === '' ? unfiltered : unfiltered.filter((i) => i.title.toLowerCase().includes(query))),
    [unfiltered, query]);

  // Bucket (filtered) issues by severity (unknown severities fall back to LOW so none vanish).
  const grouped = useMemo(() => {
    const map: Record<SevKey, IssueSummary[]> = { CRITICAL: [], HIGH: [], MEDIUM: [], LOW: [] };
    for (const issue of filtered) {
      const key = (issue.severity ?? 'LOW').toUpperCase();
      const bucket: SevKey = key === 'CRITICAL' || key === 'HIGH' || key === 'MEDIUM' ? (key as SevKey) : 'LOW';
      map[bucket].push(issue);
    }
    return map;
  }, [filtered]);

  const counts = useMemo<Record<SevKey, number>>(
    () => ({ CRITICAL: grouped.CRITICAL.length, HIGH: grouped.HIGH.length, MEDIUM: grouped.MEDIUM.length, LOW: grouped.LOW.length }),
    [grouped]);

  // Which severity groups to render: all non-empty, or just the filtered severity.
  const visibleGroups = SEV_GROUPS.filter((key) => {
    if (counts[key] === 0) return false;
    if (severity !== null) return key === severity;
    return true;
  });

  // Build the framework buckets from the (title-filtered) set. An issue appears under EACH
  // framework key it lists (multi-membership), deduped by `check` within a key; issues with no
  // framework land in a single "No framework" bucket rendered last. Keys are ordered by group
  // size (desc) then display name, with "No framework" always last.
  const frameworkBuckets = useMemo<FrameworkBucket[]>(() => {
    const map = new Map<string, { issues: IssueSummary[]; seen: Set<string> }>();
    const push = (key: string, issue: IssueSummary) => {
      let entry = map.get(key);
      if (!entry) { entry = { issues: [], seen: new Set() }; map.set(key, entry); }
      if (!entry.seen.has(issue.check)) { entry.seen.add(issue.check); entry.issues.push(issue); }
    };
    for (const issue of filtered) {
      const fws = issue.frameworks ?? [];
      if (fws.length === 0) { push(NO_FRAMEWORK_KEY, issue); continue; }
      for (const raw of fws) push(getFrameworkKey(raw), issue);
    }
    const buckets: FrameworkBucket[] = [];
    for (const [key, entry] of map) {
      if (key === NO_FRAMEWORK_KEY) continue;
      buckets.push({ key, displayName: getFwConfig(key).displayName, issues: entry.issues });
    }
    buckets.sort((a, b) => (b.issues.length - a.issues.length) || a.displayName.localeCompare(b.displayName));
    const none = map.get(NO_FRAMEWORK_KEY);
    if (none) buckets.push({ key: NO_FRAMEWORK_KEY, displayName: NO_FRAMEWORK_LABEL, issues: none.issues });
    return buckets;
  }, [filtered]);

  // Caption + grouping-tab counts reflect the active grouping over the filtered set.
  const findingsInVisibleSeverity = visibleGroups.reduce((sum, key) => sum + counts[key], 0);
  const frameworkFindingsTotal = frameworkBuckets.reduce((sum, b) => sum + b.issues.length, 0);
  const severityTabCount = SEV_GROUPS.filter((k) => counts[k] > 0).length;
  const frameworkTabCount = frameworkBuckets.length;

  const caption = grouping === 'severity'
    ? `Showing ${visibleGroups.length} rows | ${findingsInVisibleSeverity} Findings`
    : `Showing ${frameworkBuckets.length} rows | ${frameworkFindingsTotal} Findings${
        frameworkFindingsTotal > filtered.length ? ' (issues can appear under multiple frameworks)' : ''}`;

  const openIssue = async (issue: IssueSummary) => {
    setSelected(issue);
    setDetail(null);
    setDetailLoading(true);
    try {
      setDetail(await loadDetail(issue.check));
    } catch (err) {
      console.error('[XamSecure] issue detail failed:', err);
    } finally {
      setDetailLoading(false);
    }
  };

  const toggleGroup = (key: SevKey) => {
    setOpenGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };

  const toggleFramework = (key: string) => {
    setOpenFrameworks((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  };

  const clearSearch = () => setSearch('');
  const clearSeverity = () => setSeverity(null);
  const clearAll = () => { setSearch(''); setSeverity(null); };

  const titleStyle: React.CSSProperties = {
    margin: 0,
    fontSize: 24,
    lineHeight: '36px',
    fontWeight: 500,
    color: 'var(--xs-text-primary)',
    overflow: 'hidden',
    textOverflow: 'ellipsis',
    whiteSpace: 'nowrap',
  };

  // Loading: title + skeleton placeholders.
  if (isLoading && data === null) {
    return (
      <div className="xs-issues">
        <p style={titleStyle}>Issues</p>
        <div style={{ display: 'flex', gap: 8, margin: '16px 0' }}>
          {[0, 1, 2, 3, 4].map((i) => (
            <span key={i} className="xs-skeleton" style={{ height: 30, width: 96, borderRadius: 'var(--xs-radius-pill)' }} />
          ))}
        </div>
        {[0, 1, 2, 3].map((i) => (
          <span key={i} className="xs-skeleton" style={{ display: 'block', height: 40, width: '100%', margin: '12px 0' }} />
        ))}
        <p style={{ fontSize: 14, color: 'var(--xs-text-secondary)' }}>Loading issues…</p>
      </div>
    );
  }

  // Empty: no issues at all.
  if (!isLoading && unfiltered.length === 0) {
    return (
      <div className="xs-issues">
        <p style={titleStyle}>Issues</p>
        <p style={{ marginTop: 48, textAlign: 'center', fontSize: 14, color: 'var(--xs-text-secondary)' }}>
          No open issues for the selected account.
        </p>
      </div>
    );
  }

  const groupingLabel = grouping === 'severity' ? 'By severity' : 'By framework';

  return (
    <div className="xs-issues">
      <IssuesToolbar totalIssues={filtered.length} />

      {/* Filter bar: real title search + removable active-filter pills. */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 10, margin: '16px 0' }}>
        <IssuesSearch value={search} onChange={setSearch} />
        <ActiveFilters
          severity={severity}
          severityLabel={severity ? SEV_LABEL[severity as SevKey] ?? severity : undefined}
          query={search.trim()}
          onClearSeverity={clearSeverity}
          onClearSearch={clearSearch}
          onClearAll={clearAll}
        />
      </div>

      {/* Grouping switch + caption. Severity chips only apply in severity mode. */}
      <div style={{ display: 'flex', flexWrap: 'wrap', alignItems: 'center', gap: 16, marginBottom: 12 }}>
        <GroupingTabs
          grouping={grouping}
          onChange={setGrouping}
          severityCount={severityTabCount}
          frameworkCount={frameworkTabCount}
        />
        <span style={{ fontSize: 12, lineHeight: '18px', color: 'var(--xs-text-secondary)' }}>{caption}</span>
      </div>

      {grouping === 'severity' && (
        <div style={{ marginBottom: 16 }}>
          <SeverityQuickFilter
            counts={counts}
            total={filtered.length}
            active={severity}
            onChange={(v) => {
              setSeverity(v);
              // A chosen severity auto-expands its group so the user sees the result immediately.
              if (v !== null) setOpenGroups(new Set([v as SevKey]));
            }}
          />
        </div>
      )}

      {/* Full-width list surface: a single list-header row naming the active grouping axis,
          group rows beneath. Column labels live inside each table. */}
      <div
        style={{
          height: 43,
          display: 'flex',
          alignItems: 'center',
          paddingLeft: 8,
          borderBottom: '1px solid var(--xs-border-subtle)',
          fontSize: 14,
          fontWeight: 400,
          color: 'var(--xs-text-secondary)',
        }}
      >
        {groupingLabel}
      </div>

      {grouping === 'severity' ? (
        visibleGroups.length === 0 ? (
          <p style={{ marginTop: 48, textAlign: 'center', fontSize: 14, color: 'var(--xs-text-secondary)' }}>
            {query !== ''
              ? `No issues match "${search.trim()}". Clear the search to see all issues.`
              : severity !== null
                ? `No ${severity.toLowerCase()}-severity issues. Clear the filter to see all issues.`
                : 'No open issues for the selected account.'}
          </p>
        ) : (
          visibleGroups.map((key) => (
            <SeverityGroup
              key={key}
              severity={key}
              label={SEV_LABEL[key]}
              issues={grouped[key]}
              open={openGroups.has(key)}
              onToggle={() => toggleGroup(key)}
              onOpenIssue={openIssue}
            />
          ))
        )
      ) : (
        frameworkBuckets.length === 0 ? (
          <p style={{ marginTop: 48, textAlign: 'center', fontSize: 14, color: 'var(--xs-text-secondary)' }}>
            {query !== ''
              ? `No issues match "${search.trim()}". Clear the search to see all issues.`
              : 'No open issues for the selected account.'}
          </p>
        ) : (
          frameworkBuckets.map((bucket) => (
            <FrameworkGroup
              key={bucket.key}
              frameworkKey={bucket.key}
              displayName={bucket.displayName}
              issues={bucket.issues}
              open={openFrameworks.has(bucket.key)}
              onToggle={() => toggleFramework(bucket.key)}
              onOpenIssue={openIssue}
            />
          ))
        )
      )}

      {selected && (
        <IssueDrawer
          title={selected.title}
          detail={detail}
          isLoading={detailLoading}
          onClose={() => { setSelected(null); setDetail(null); }}
          onOpenResource={(id) => { setSelected(null); setDetail(null); open(id); }}
        />
      )}
      {drawer}
    </div>
  );
};

export default IssuesTab;
