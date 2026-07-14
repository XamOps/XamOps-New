import React from 'react';
import type { ComplianceFramework } from '../../types/xamsecure';
import { FrameworkLogo } from '../../utils/frameworkBranding';

interface Props {
  data: ComplianceFramework[] | null;
  isLoading: boolean;
}

const fmtDate = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleDateString(undefined, { month: 'short', day: '2-digit', year: 'numeric' }) : '—';

const ComplianceTab: React.FC<Props> = ({ data, isLoading }) => {
  const frameworks = data ?? [];
  if (isLoading && frameworks.length === 0) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading compliance frameworks...</div>;
  }
  if (frameworks.length === 0) {
    return (
      <div className="text-center py-16 bg-white border border-gray-200 rounded-xl shadow-sm text-sm text-gray-400">
        No compliance framework data for the selected account.
      </div>
    );
  }
  return (
    <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
      {frameworks.map((f) => {
        const total = f.failCount + f.passCount;
        const passPct = total === 0 ? 0 : Math.round((f.passCount / total) * 100);
        // Prefer Upwind's framework compliance score; fall back to our tenant pass rate.
        const score = f.score ?? passPct;
        return (
          <div key={f.framework} className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-2.5 min-w-0">
                <FrameworkLogo framework={f.framework} size={32} />
                <div className="min-w-0">
                <p className="text-sm font-bold text-gray-900">{f.framework}</p>
                <div className="flex items-center gap-2 mt-1">
                  {f.version && (
                    <span className="text-[10px] font-semibold text-gray-500">v{f.version}</span>
                  )}
                  {f.status && (
                    <span className={`text-[9px] font-bold uppercase tracking-wide px-1.5 py-0.5 rounded-full ${
                      f.status.toUpperCase() === 'ENABLED' ? 'bg-gray-900 text-white' : 'bg-gray-100 text-gray-500'}`}>
                      {f.status}
                    </span>
                  )}
                </div>
                </div>
              </div>
              <p className={`text-2xl font-extrabold flex-shrink-0 ${
                score >= 80 ? 'text-emerald-600' : score >= 50 ? 'text-yellow-600' : 'text-red-600'}`}>{score}%</p>
            </div>
            <div className="mt-3 h-1.5 bg-gray-100 rounded-full overflow-hidden">
              <div className={`h-full ${score >= 80 ? 'bg-emerald-500' : score >= 50 ? 'bg-yellow-400' : 'bg-red-500'}`}
                   style={{ width: `${score}%` }} />
            </div>
            <div className="flex items-center justify-between mt-3 text-xs text-gray-500">
              <div className="flex gap-4">
                <span><span className="font-bold text-gray-900">{f.passCount}</span> passing</span>
                <span><span className="font-bold text-gray-900">{f.failCount}</span> failing</span>
              </div>
              {f.lastScanTime && (
                <span className="text-[10px] text-gray-400">Scanned {fmtDate(f.lastScanTime)}</span>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
};

export default ComplianceTab;
