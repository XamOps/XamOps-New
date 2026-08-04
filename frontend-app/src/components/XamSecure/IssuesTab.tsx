import React, { useMemo, useState } from 'react';
import type { IssueSummary, IssueDetail, ResourceDetail } from '../../types/xamsecure';
import { SEVERITY_ORDER } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import IssueDrawer from './IssueDrawer';
import Pager, { usePaged } from './Pager';
import FilterChips, { SEVERITY_TINT } from './FilterChips';
import { useResourceDrawer } from './useResourceDrawer';
import { FrameworkLogo, hasFrameworkLogo } from '../../utils/frameworkBranding';

interface Props {
  data: IssueSummary[] | null;
  isLoading: boolean;
  loadDetail: (check: string) => Promise<IssueDetail>;
  loadResourceDetail?: (resourceId: string) => Promise<ResourceDetail>;
}

/**
 * Issues view — the default XamSecure landing. Failing posture checks collapsed
 * into one actionable row each (grouped by check across all affected resources).
 * Clicking a row opens the read-only detail drawer.
 */
const IssuesTab: React.FC<Props> = ({ data, isLoading, loadDetail, loadResourceDetail }) => {
  const [selected, setSelected] = useState<IssueSummary | null>(null);
  const [detail, setDetail] = useState<IssueDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const { open, drawer } = useResourceDrawer(loadResourceDetail);
  const [severity, setSeverity] = useState<string | null>(null);
  const unfiltered = data ?? [];

  // Severity distribution, ordered by seriousness rather than by count, so the chips
  // always read Critical → Low regardless of which bucket happens to be largest.
  const severityGroups = useMemo(() => {
    const counts = new Map<string, number>();
    for (const r of unfiltered) {
      const key = (r.severity ?? 'LOW').toUpperCase();
      counts.set(key, (counts.get(key) ?? 0) + 1);
    }
    return SEVERITY_ORDER
      .filter((s) => counts.has(s))
      .map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase(), count: counts.get(s)! }));
  }, [unfiltered]);

  const allRows = useMemo(
    () => (severity === null
      ? unfiltered
      : unfiltered.filter((r) => (r.severity ?? 'LOW').toUpperCase() === severity)),
    [unfiltered, severity]);

  const { page, setPage, pageItems: rows, totalPages, total } = usePaged(allRows, 25);

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

  const totalResources = allRows.reduce((sum, r) => sum + r.resourceCount, 0);

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Issues</p>
        {unfiltered.length > 0 && (
          <span className="text-[10px] font-semibold text-gray-500">
            {unfiltered.length} {unfiltered.length === 1 ? 'issue' : 'issues'} · {totalResources} affected resources
          </span>
        )}
      </div>
      {severityGroups.length > 1 && (
        <div className="px-5 py-3 border-b border-gray-100">
          <FilterChips
            options={severityGroups}
            active={severity}
            allCount={unfiltered.length}
            tint={(v) => SEVERITY_TINT[v]}
            onChange={(v) => { setSeverity(v); setPage(0); }}
          />
        </div>
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Severity</th>
              <th className="px-5 py-2.5">Issue</th>
              <th className="px-5 py-2.5">Resources</th>
              <th className="px-5 py-2.5">Frameworks</th>
              <th className="px-5 py-2.5">First Seen</th>
              <th className="px-5 py-2.5">Last Evaluation</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((issue) => (
              <tr key={issue.check}
                  className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer"
                  onClick={() => openIssue(issue)}>
                <td className="px-5 py-3"><SeverityChip severity={issue.severity} /></td>
                <td className="px-5 py-3 font-medium text-gray-800 max-w-md truncate">{issue.title}</td>
                <td className="px-5 py-3 font-semibold text-gray-700">{issue.resourceCount}</td>
                <td className="px-5 py-3">
                  <div className="flex items-center flex-wrap gap-1">
                    {issue.frameworks.slice(0, 5).map((f) => (
                      hasFrameworkLogo(f)
                        ? <FrameworkLogo key={f} framework={f} size={22} />
                        : <span key={f}
                                title={f}
                                className="inline-block px-1.5 py-0.5 rounded text-[10px] font-semibold bg-gray-100 text-gray-600 max-w-[130px] truncate">
                            {f}
                          </span>
                    ))}
                    {issue.frameworks.length > 5 && (
                      <span className="text-[10px] text-gray-400 font-semibold ml-0.5">
                        +{issue.frameworks.length - 5}
                      </span>
                    )}
                    {issue.frameworks.length === 0 && <span className="text-[10px] text-gray-300">—</span>}
                  </div>
                </td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {issue.firstSeen ? new Date(issue.firstSeen).toLocaleDateString() : '—'}
                </td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {issue.lastEvaluation ? new Date(issue.lastEvaluation).toLocaleDateString() : '—'}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  {isLoading
                    ? 'Loading issues...'
                    : severity !== null
                      ? `No ${severity.toLowerCase()}-severity issues. Clear the filter to see all issues.`
                      : 'No open issues for the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      <Pager page={page} totalPages={totalPages} total={total} unit="issues" onPage={setPage} />
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
