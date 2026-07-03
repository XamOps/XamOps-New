import React from 'react';
import type { XamSecureSummary } from '../../types/xamsecure';
import { SEVERITY_ORDER, severityTotal } from '../../types/xamsecure';

interface Props {
  summary: XamSecureSummary | null;
  isLoading: boolean;
}

const Card: React.FC<{ label: string; counts: Record<string, number> | undefined }> = ({ label, counts }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
    <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{label}</p>
    <p className="text-3xl font-bold text-gray-900 mt-1">{severityTotal(counts)}</p>
    <div className="flex gap-3 mt-3">
      {SEVERITY_ORDER.map((sev) => (
        <div key={sev} className="text-center">
          <p className="text-sm font-bold text-gray-800">{counts?.[sev] ?? 0}</p>
          <p className="text-[10px] text-gray-400 uppercase">{sev.slice(0, 4)}</p>
        </div>
      ))}
    </div>
  </div>
);

const OverviewTab: React.FC<Props> = ({ summary, isLoading }) => {
  if (isLoading && !summary) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading security overview...</div>;
  }
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <Card label="Open Threats" counts={summary?.threatsBySeverity} />
        <Card label="Vulnerabilities" counts={summary?.vulnerabilitiesBySeverity} />
        <Card label="Posture Findings" counts={summary?.postureBySeverity} />
      </div>
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-3">Data Freshness</p>
        <div className="space-y-1.5">
          {(summary?.syncState ?? []).map((s) => (
            <div key={s.domain} className="flex items-center justify-between text-xs">
              <span className="font-medium text-gray-700">{s.domain}</span>
              <span className={s.lastError ? 'text-gray-900 font-semibold' : 'text-gray-400'}>
                {s.lastError
                  ? `Sync issue — showing last data (${s.lastError})`
                  : s.lastSuccessAt
                    ? `Last synced ${new Date(s.lastSuccessAt).toLocaleString()}`
                    : 'Not synced yet'}
              </span>
            </div>
          ))}
          {(summary?.syncState ?? []).length === 0 && (
            <p className="text-xs text-gray-400">Sync has not run yet.</p>
          )}
        </div>
      </div>
    </div>
  );
};

export default OverviewTab;
