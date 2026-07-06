import React, { useState } from 'react';
import type { PageResp, ThreatDetection } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import FindingDrawer from './FindingDrawer';

interface Props {
  threats: PageResp<ThreatDetection> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
  onArchive?: (upwindId: string) => void;
}

const ThreatsTab: React.FC<Props> = ({ threats, isLoading, onPage, onArchive }) => {
  const [selected, setSelected] = useState<ThreatDetection | null>(null);
  const rows = threats?.content ?? [];

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Runtime Threat Detections</p>
        <span className="flex items-center gap-1.5 text-[10px] font-semibold text-gray-500">
          <span className="w-1.5 h-1.5 rounded-full bg-gray-900 animate-pulse" />
          LIVE — refreshes every 60s
        </span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-5 py-2.5">Last Seen</th>
              <th className="px-5 py-2.5">Severity</th>
              <th className="px-5 py-2.5">Detection</th>
              <th className="px-5 py-2.5">Resource</th>
              <th className="px-5 py-2.5">MITRE Tactic</th>
              <th className="px-5 py-2.5">Count</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t) => (
              <tr key={t.upwindId}
                  className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer"
                  onClick={() => setSelected(t)}>
                <td className="px-5 py-3 whitespace-nowrap text-gray-500">
                  {t.lastSeenTime ? new Date(t.lastSeenTime).toLocaleString() : '—'}
                </td>
                <td className="px-5 py-3"><SeverityChip severity={t.severity} /></td>
                <td className="px-5 py-3 font-medium text-gray-800 max-w-md truncate">{t.title}</td>
                <td className="px-5 py-3 text-gray-600">
                  {t.resourceName}
                  <span className="block text-[10px] text-gray-400">{t.resourceType}</span>
                </td>
                <td className="px-5 py-3 text-gray-600">{t.mitreTactics ?? '—'}</td>
                <td className="px-5 py-3 text-gray-600">{t.occurrenceCount ?? 1}</td>
              </tr>
            ))}
            {rows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-5 py-12 text-center text-gray-400">
                  {isLoading ? 'Loading detections...' : 'No threat detections for the selected account.'}
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
      {threats && threats.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
          <span>Page {threats.number + 1} of {threats.totalPages} · {threats.totalElements} detections</span>
          <div className="flex gap-2">
            <button disabled={threats.number === 0} onClick={() => onPage(threats.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
            <button disabled={threats.number + 1 >= threats.totalPages} onClick={() => onPage(threats.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
          </div>
        </div>
      )}
      {selected && (
        <FindingDrawer
          title={selected.title ?? 'Detection'}
          rawPayload={selected.rawPayload}
          onClose={() => setSelected(null)}
          actions={onArchive && (
            <button
              onClick={() => {
                onArchive(selected.upwindId);
                setSelected(null);
              }}
              className="px-3 py-1.5 bg-gray-900 hover:bg-black text-white text-xs font-bold rounded-xl"
            >
              Archive
            </button>
          )}
        />
      )}
    </div>
  );
};

export default ThreatsTab;
