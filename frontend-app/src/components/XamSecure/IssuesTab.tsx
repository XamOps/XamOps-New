import React, { useState } from 'react';
import type { IssueSummary, IssueDetail } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import IssueDrawer from './IssueDrawer';
import Pager, { usePaged } from './Pager';
import { FrameworkLogo, hasFrameworkLogo } from '../../utils/frameworkBranding';

interface Props {
  data: IssueSummary[] | null;
  isLoading: boolean;
  loadDetail: (check: string) => Promise<IssueDetail>;
}

/**
 * Issues view — the default XamSecure landing. Failing posture checks collapsed
 * into one actionable row each (grouped by check across all affected resources).
 * Clicking a row opens the read-only detail drawer.
 */
const IssuesTab: React.FC<Props> = ({ data, isLoading, loadDetail }) => {
  const [selected, setSelected] = useState<IssueSummary | null>(null);
  const [detail, setDetail] = useState<IssueDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const allRows = data ?? [];
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
        {allRows.length > 0 && (
          <span className="text-[10px] font-semibold text-gray-500">
            {allRows.length} {allRows.length === 1 ? 'issue' : 'issues'} · {totalResources} affected resources
          </span>
        )}
      </div>
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
                  {isLoading ? 'Loading issues...' : 'No open issues for the selected account.'}
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
        />
      )}
    </div>
  );
};

export default IssuesTab;
