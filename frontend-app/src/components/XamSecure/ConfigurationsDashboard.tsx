import React, { useMemo } from 'react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, Legend as ReLegend,
} from 'recharts';
import type { ConfigurationsDashboard as Dashboard } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import { FrameworkLogo, hasFrameworkLogo } from '../../utils/frameworkBranding';

interface Props {
  data: Dashboard | null;
  isLoading: boolean;
}

/** App severity palette (Critical red / High orange / Medium yellow / Low green). */
const SEV = { CRITICAL: '#dc2626', HIGH: '#ea580c', MEDIUM: '#eab308', LOW: '#16a34a', OTHER: '#9ca3af' };

/**
 * Configurations "Dashboard" sub-view — mirrors Upwind's Configurations dashboard using
 * only real data we sync: severity summary, findings-over-time, top configuration findings,
 * and per-framework compliance scores. Panels Upwind shows that we have no data source for
 * (per-finding impact %, remediation difficulty, resolved-over-time) are intentionally omitted.
 */
const ConfigurationsDashboard: React.FC<Props> = ({ data, isLoading }) => {
  const sev = data?.severityCounts ?? {};
  const scoreColor = (n: number) => (n >= 80 ? 'text-emerald-600' : n >= 60 ? 'text-yellow-600' : 'text-red-600');
  const barColor = (n: number) => (n >= 80 ? 'bg-emerald-500' : n >= 60 ? 'bg-yellow-400' : 'bg-red-500');

  const overTime = useMemo(() => data?.findingsOverTime ?? [], [data]);

  if (isLoading && !data) {
    return <div className="text-center py-16 text-sm text-gray-400">Loading configurations dashboard...</div>;
  }
  if (!data) {
    return <div className="text-center py-16 text-sm text-gray-400">No configuration data for the selected account.</div>;
  }

  const severityRows: Array<{ key: keyof typeof SEV; label: string }> = [
    { key: 'CRITICAL', label: 'Critical' },
    { key: 'HIGH', label: 'High' },
    { key: 'MEDIUM', label: 'Medium' },
    { key: 'LOW', label: 'Low' },
  ];

  return (
    <div className="space-y-4">
      {/* Top row: compliance score + findings summary */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
        {/* Compliance score */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-6 flex flex-col items-center justify-center">
          {data.avgComplianceScore != null ? (
            <>
              <p className={`text-6xl font-bold ${scoreColor(data.avgComplianceScore)}`}>
                {data.avgComplianceScore}%
              </p>
              <p className="text-sm font-semibold text-gray-700 mt-2">Compliance score</p>
              <p className="text-[11px] text-gray-400 mt-1 text-center">
                Average across {data.frameworkCount} scanned {data.frameworkCount === 1 ? 'framework' : 'frameworks'}
              </p>
            </>
          ) : (
            <p className="text-sm text-gray-400">No framework scores yet</p>
          )}
        </div>

        {/* Findings summary by severity */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5 lg:col-span-2">
          <div className="flex items-center justify-between mb-3">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Configurations findings summary</p>
            <span className="text-[11px] font-semibold text-gray-500">
              {data.totalFindings} findings · {data.newLast7Days} new in 7d
            </span>
          </div>
          <div className="grid grid-cols-4 gap-3">
            {severityRows.map(({ key, label }) => (
              <div key={key} className="rounded-lg border border-gray-100 p-3 text-center">
                <p className="text-2xl font-bold" style={{ color: SEV[key] }}>{sev[key] ?? 0}</p>
                <p className="text-[11px] font-medium text-gray-500 mt-0.5">{label}</p>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Findings over time */}
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide mb-1">Findings over time</p>
        <p className="text-[10px] text-gray-400 mb-3">Cumulative failing findings · last 7 days</p>
        <div style={{ width: '100%', height: 220 }}>
          <ResponsiveContainer width="100%" height="100%">
            <AreaChart data={overTime} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
              <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#f3f4f6" />
              <XAxis dataKey="date" tick={{ fontSize: 9, fill: '#9ca3af' }} axisLine={false} tickLine={false} />
              <YAxis tick={{ fontSize: 9, fill: '#9ca3af' }} axisLine={false} tickLine={false} width={34} />
              <Tooltip />
              <ReLegend iconType="circle" wrapperStyle={{ fontSize: 11 }} />
              <Area type="monotone" dataKey="HIGH" name="Critical/High" stackId="1"
                    stroke={SEV.HIGH} fill={SEV.HIGH} fillOpacity={0.8} />
              <Area type="monotone" dataKey="OTHER" name="Other" stackId="1"
                    stroke={SEV.OTHER} fill={SEV.OTHER} fillOpacity={0.7} />
            </AreaChart>
          </ResponsiveContainer>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
        {/* Top configuration findings */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
          <div className="px-5 py-3 border-b border-gray-100">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">
              Top configuration findings ({data.topFindings.length})
            </p>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
                  <th className="px-5 py-2">Finding</th>
                  <th className="px-5 py-2">Severity</th>
                  <th className="px-5 py-2 text-right">Affected resources</th>
                </tr>
              </thead>
              <tbody>
                {data.topFindings.map((f) => (
                  <tr key={f.check} className="border-b border-gray-50 last:border-0">
                    <td className="px-5 py-2.5 font-medium text-gray-800 max-w-xs truncate">{f.title}</td>
                    <td className="px-5 py-2.5"><SeverityChip severity={f.severity} /></td>
                    <td className="px-5 py-2.5 text-right font-semibold text-gray-700">{f.resourceCount}</td>
                  </tr>
                ))}
                {data.topFindings.length === 0 && (
                  <tr><td colSpan={3} className="px-5 py-10 text-center text-gray-400">No findings.</td></tr>
                )}
              </tbody>
            </table>
          </div>
        </div>

        {/* Compliance score per framework */}
        <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
          <div className="px-5 py-3 border-b border-gray-100">
            <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Compliance score per framework</p>
          </div>
          <div className="max-h-[360px] overflow-y-auto divide-y divide-gray-50">
            {data.frameworkScores.map((fw) => (
              <div key={fw.framework} className="flex items-center gap-3 px-5 py-2.5">
                {hasFrameworkLogo(fw.framework) && <FrameworkLogo framework={fw.framework} size={18} />}
                <span className="flex-1 text-xs text-gray-700 truncate" title={fw.framework}>{fw.framework}</span>
                <div className="w-28 h-1.5 bg-gray-100 rounded-full overflow-hidden">
                  <div className={`h-full ${barColor(fw.score ?? 0)}`} style={{ width: `${fw.score ?? 0}%` }} />
                </div>
                <span className={`text-xs font-bold w-10 text-right ${scoreColor(fw.score ?? 0)}`}>
                  {fw.score ?? 0}%
                </span>
              </div>
            ))}
            {data.frameworkScores.length === 0 && (
              <div className="px-5 py-10 text-center text-gray-400 text-xs">No scanned frameworks.</div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
};

export default ConfigurationsDashboard;
