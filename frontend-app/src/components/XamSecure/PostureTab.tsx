import React, { useMemo, useState } from 'react';
import type { PageResp, PostureFinding, ResourceDetail } from '../../types/xamsecure';
import { SEVERITY_ORDER } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import PostureFindingDrawer from './PostureFindingDrawer';
import ResourceLink from './ResourceLink';
import FilterChips, { SEVERITY_TINT } from './FilterChips';
import { useResourceDrawer } from './useResourceDrawer';

interface Props {
  data: PageResp<PostureFinding> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
  loadResourceDetail?: (resourceId: string) => Promise<ResourceDetail>;
  /** Severity distribution for the chip counts (from the Configurations dashboard). */
  severityCounts?: Record<string, number>;
  /** Server-side severity filter — the table is paginated, so filtering can't be local. */
  onSeverity?: (severity: string) => void;
}

const PostureTab: React.FC<Props> = ({
  data, isLoading, onPage, loadResourceDetail, severityCounts, onSeverity,
}) => {
  const [selected, setSelected] = useState<PostureFinding | null>(null);
  const [severity, setSeverity] = useState<string | null>(null);
  const { open, drawer } = useResourceDrawer(loadResourceDetail);
  const rows = data?.content ?? [];

  const severityGroups = useMemo(() => {
    const counts = severityCounts ?? {};
    return SEVERITY_ORDER
      .filter((s) => (counts[s] ?? 0) > 0)
      .map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase(), count: counts[s] }));
  }, [severityCounts]);

  const totalFindings = useMemo(
    () => Object.values(severityCounts ?? {}).reduce((a, b) => a + b, 0),
    [severityCounts]);

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      {onSeverity && severityGroups.length > 1 && (
        <div className="px-5 py-3 border-b border-gray-100">
          <FilterChips
            options={severityGroups}
            active={severity}
            allCount={totalFindings}
            tint={(v) => SEVERITY_TINT[v]}
            onChange={(v) => { setSeverity(v); onSeverity(v ?? ''); }}
          />
        </div>
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Severity</th>
              <th className="px-5 py-2.5">Status</th>
              <th className="px-5 py-2.5">Finding</th>
              <th className="px-5 py-2.5">Resource</th>
              <th className="px-5 py-2.5">Provider</th>
              <th className="px-5 py-2.5">Last Seen</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((p) => (
              <tr key={p.upwindId}
                  className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer"
                  onClick={() => setSelected(p)}>
                <td className="px-5 py-3"><SeverityChip severity={p.severity} /></td>
                <td className={`px-5 py-3 font-semibold ${
                  p.status === 'FAIL' ? 'text-red-600' : p.status === 'PASS' ? 'text-emerald-600' : 'text-gray-500'}`}>
                  {p.status}
                </td>
                <td className="px-5 py-3 font-medium text-gray-800 max-w-md truncate">{p.title}</td>
                <td className="px-5 py-3 text-gray-600">
                  <ResourceLink resourceId={p.resourceId} label={p.resourceName}
                                sub={p.resourceType} onOpen={open} />
                </td>
                <td className="px-5 py-3 text-gray-600">{p.cloudProvider}</td>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {p.lastSeenTime ? new Date(p.lastSeenTime).toLocaleDateString() : '—'}
                </td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  {isLoading ? 'Loading posture findings...' : 'No posture findings for the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {data && data.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
          <span>Page {data.number + 1} of {data.totalPages} · {data.totalElements} findings</span>
          <div className="flex gap-2">
            <button disabled={data.number === 0} onClick={() => onPage(data.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
            <button disabled={data.number + 1 >= data.totalPages} onClick={() => onPage(data.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
          </div>
        </div>
      )}
      {selected && (
        <PostureFindingDrawer
          finding={selected}
          onClose={() => setSelected(null)}
          onOpenResource={(id) => { setSelected(null); open(id); }}
        />
      )}
      {drawer}
    </div>
  );
};

export default PostureTab;
