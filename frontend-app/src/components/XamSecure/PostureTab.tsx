import React, { useState } from 'react';
import type { PageResp, PostureFinding } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import FindingDrawer from './FindingDrawer';

interface Props {
  data: PageResp<PostureFinding> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
}

const PostureTab: React.FC<Props> = ({ data, isLoading, onPage }) => {
  const [selected, setSelected] = useState<PostureFinding | null>(null);
  const rows = data?.content ?? [];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
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
                <td className="px-5 py-3 font-semibold text-gray-700">{p.status}</td>
                <td className="px-5 py-3 font-medium text-gray-800 max-w-md truncate">{p.title}</td>
                <td className="px-5 py-3 text-gray-600">
                  {p.resourceName}
                  <span className="block text-[10px] text-gray-400">{p.resourceType}</span>
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
        <FindingDrawer title={selected.title ?? 'Posture finding'} rawPayload={selected.rawPayload}
                       onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

export default PostureTab;
