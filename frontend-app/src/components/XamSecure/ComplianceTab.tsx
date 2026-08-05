import React, { useMemo, useState } from 'react';
import type { ComplianceFramework, IssueSummary } from '../../types/xamsecure';
import { FrameworkLogo } from '../../utils/frameworkBranding';
import FrameworkDrawer from './FrameworkDrawer';

interface Props {
  data: ComplianceFramework[] | null;
  isLoading: boolean;
  /** Issues already loaded for this account, used to list a framework's failing controls. */
  issues?: IssueSummary[] | null;
}

/** Normalizes framework titles so issue rows match the framework card reliably. */
const normalize = (s: string): string => s.trim().replace(/\s+/g, ' ').toLowerCase();

const fmtDate = (iso: string | null | undefined) =>
  iso ? new Date(iso).toLocaleDateString(undefined, { month: 'short', day: '2-digit', year: 'numeric' }) : '—';

const ComplianceTab: React.FC<Props> = ({ data, isLoading, issues }) => {
  const [selected, setSelected] = useState<ComplianceFramework | null>(null);

  // Issues carry the frameworks each check maps to, so a framework's failing controls can
  // be derived locally instead of adding a request.
  const failingChecks = useMemo(() => {
    if (!selected) return [];
    const target = normalize(selected.framework);
    return (issues ?? []).filter((i) => i.frameworks.some((f) => normalize(f) === target));
  }, [selected, issues]);

  // Hide frameworks that were never scanned against this account (e.g. Azure/GCP/OCI/K8s
  // frameworks on an AWS-only account). Those show a hollow "0% / No failing controls"
  // that reads as broken. Keep anything actually evaluated: has a scan time, has failing
  // controls, or carries a real (>0) compliance score.
  const frameworks = (data ?? [])
    .filter((f) => f.lastScanTime || f.failCount > 0 || (f.score != null && f.score > 0))
    .sort((a, b) => (a.score ?? 0) - (b.score ?? 0) || b.failCount - a.failCount);
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
          <button key={f.framework} type="button" onClick={() => setSelected(f)}
                  className="text-left bg-white border border-gray-200 rounded-xl shadow-sm p-5 hover:border-gray-400 transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-gray-900">
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
            <div className="flex items-center justify-between gap-2 mt-3 text-xs text-gray-500">
              {/* Passing counts are real now. The sync used to omit the status parameter, and
                  Upwind's endpoint defaults to FAIL, so passCount was structurally zero and
                  this line could only ever show failures. */}
              <span className="flex items-center gap-2 min-w-0">
                {f.failCount > 0 ? (
                  <span><span className="font-bold text-red-600">{f.failCount}</span> failing</span>
                ) : (
                  <span className="font-semibold text-emerald-600">No failing controls</span>
                )}
                {f.passCount > 0 && (
                  <span className="text-gray-400">
                    <span className="font-bold text-emerald-600">{f.passCount}</span> passing
                  </span>
                )}
              </span>
              {f.lastScanTime && (
                <span className="text-[10px] text-gray-400 flex-shrink-0">Scanned {fmtDate(f.lastScanTime)}</span>
              )}
            </div>
          </button>
        );
      })}
      {selected && (
        <FrameworkDrawer framework={selected} failingChecks={failingChecks}
                         onClose={() => setSelected(null)} />
      )}
    </div>
  );
};

export default ComplianceTab;
