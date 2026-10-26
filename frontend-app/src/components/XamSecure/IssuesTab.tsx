import React, { useMemo, useState } from 'react';
import type { IssueSummary, IssueDetail, ResourceDetail } from '../../types/xamsecure';
import type { SevKey } from './ui/tokens';
import IssueDrawer from './IssueDrawer';
import { useResourceDrawer } from './useResourceDrawer';
import SeverityQuickFilter from './ui/SeverityQuickFilter';
import SeverityGroup from './ui/SeverityGroup';
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

/**
 * Issues view — the default XamSecure landing. Failing posture checks collapsed into one
 * actionable row each (grouped by check across all affected resources), then grouped by severity
 * into collapsible groups. Each group reveals a fixed-height, internally-scrollable findings
 * table; clicking a finding opens the read-only detail drawer. Light-only, token-driven.
 */
const IssuesTab: React.FC<Props> = ({ data, isLoading, loadDetail, loadResourceDetail }) => {
  const [selected, setSelected] = useState<IssueSummary | null>(null);
  const [detail, setDetail] = useState<IssueDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const { open, drawer } = useResourceDrawer(loadResourceDetail);
  const [severity, setSeverity] = useState<string | null>(null);
  const [openGroups, setOpenGroups] = useState<Set<SevKey>>(new Set());
  const unfiltered = data ?? [];

  // Bucket issues by severity (unknown severities fall back to LOW so none vanish).
  const grouped = useMemo(() => {
    const map: Record<SevKey, IssueSummary[]> = { CRITICAL: [], HIGH: [], MEDIUM: [], LOW: [] };
    for (const issue of unfiltered) {
      const key = (issue.severity ?? 'LOW').toUpperCase();
      const bucket: SevKey = key === 'CRITICAL' || key === 'HIGH' || key === 'MEDIUM' ? (key as SevKey) : 'LOW';
      map[bucket].push(issue);
    }
    return map;
  }, [unfiltered]);

  const counts = useMemo<Record<SevKey, number>>(
    () => ({ CRITICAL: grouped.CRITICAL.length, HIGH: grouped.HIGH.length, MEDIUM: grouped.MEDIUM.length, LOW: grouped.LOW.length }),
    [grouped]);

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

  // Which groups to render: all non-empty, or just the filtered severity.
  const visibleGroups = SEV_GROUPS.filter((key) => {
    if (counts[key] === 0) return false;
    if (severity !== null) return key === severity;
    return true;
  });

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

  return (
    <div className="xs-issues">
      <p style={titleStyle}>Issues</p>

      <div style={{ margin: '16px 0' }}>
        <SeverityQuickFilter
          counts={counts}
          total={unfiltered.length}
          active={severity}
          onChange={(v) => {
            setSeverity(v);
            // A chosen severity auto-expands its group so the user sees the result immediately.
            if (v !== null) setOpenGroups(new Set([v as SevKey]));
          }}
        />
      </div>

      {/* Single list-header row naming the (only) grouping axis. Column labels live inside each table. */}
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
        By severity
      </div>

      {visibleGroups.length === 0 ? (
        <p style={{ marginTop: 48, textAlign: 'center', fontSize: 14, color: 'var(--xs-text-secondary)' }}>
          {severity !== null
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
