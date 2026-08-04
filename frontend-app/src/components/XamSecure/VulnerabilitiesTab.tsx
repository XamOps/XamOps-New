import React, { useState, useMemo } from 'react';
import { Icon } from '@iconify/react';
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip, BarChart, Bar, XAxis, YAxis,
  AreaChart, Area, Legend as ReLegend } from 'recharts';
import type { FunnelStage } from '../../types/xamsecure';
import Pager, { usePaged } from './Pager';
import type {
  PageResp, VulnerabilityDashboard, CveGroupRow, CveResourceFinding, ResourceDetail,
} from '../../types/xamsecure';
import { SEVERITY_ORDER } from '../../types/xamsecure';
import SeverityChip from './SeverityChip';
import ResourceLink from './ResourceLink';
import FilterChips, { SEVERITY_TINT } from './FilterChips';
import { useResourceDrawer } from './useResourceDrawer';

/** Upwind severity palette. */
const SEV_COLOR = { Critical: '#dc2626', High: '#f97316', Medium: '#eab308', Other: '#3b82f6' } as const;

interface Props {
  dashboard: VulnerabilityDashboard | null;
  byCve: PageResp<CveGroupRow> | null;
  isLoading: boolean;
  onCvePage: (page: number) => void;
  loadCveFindings: (cveId: string) => Promise<CveResourceFinding[]>;
  loadResourceDetail?: (resourceId: string) => Promise<ResourceDetail>;
  /** Server-side severity filter for the CVE list (it is paginated). */
  onCveSeverity?: (severity: string) => void;
}

const WORKLOAD_LABEL: Record<string, string> = {
  AWS_INSTANCE: 'Hosts', AWS_LAMBDA_FUNCTION: 'Serverless', AWS_ECS_TASK: 'Containers', OTHER: 'Other',
};
const workloadLabel = (t: string) => WORKLOAD_LABEL[t] ?? t.replace(/^AWS_/, '').replace(/_/g, ' ');

function last7(): { key: string; label: string }[] {
  const out: { key: string; label: string }[] = [];
  for (let i = 6; i >= 0; i--) {
    const d = new Date(); d.setHours(0, 0, 0, 0); d.setDate(d.getDate() - i);
    out.push({ key: d.toISOString().slice(0, 10), label: d.toLocaleDateString(undefined, { month: 'short', day: '2-digit' }) });
  }
  return out;
}

const fmtDate = (iso: string | null) => (iso ? new Date(iso).toLocaleDateString() : '—');

const VulnerabilitiesTab: React.FC<Props> = ({
  dashboard, byCve, isLoading, onCvePage, loadCveFindings, loadResourceDetail, onCveSeverity,
}) => {
  const [view, setView] = useState<'dashboard' | 'findings'>('dashboard');
  const days = useMemo(() => last7(), []);
  const { open, drawer } = useResourceDrawer(loadResourceDetail);

  const sev = dashboard?.severityCounts ?? {};
  const critical = sev.CRITICAL ?? 0;
  const high = sev.HIGH ?? 0;
  const other = (sev.MEDIUM ?? 0) + (sev.LOW ?? 0) +
    Object.entries(sev).filter(([k]) => !['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'].includes(k)).reduce((a, [, v]) => a + v, 0);
  const totalCves = dashboard?.totalCves ?? 0;
  const newSev = dashboard?.newLast7Days ?? {};
  const overTime = dashboard?.overTime ?? {};
  const trend = days.map((d) => ({ label: d.label, value: overTime[d.key] ?? 0 }));

  // Upwind-style cumulative trends (findings-level), from the backend.
  const severityOverTime = dashboard?.severityOverTime ?? [];
  const criticalWorkloadOverTime = dashboard?.criticalWorkloadOverTime ?? [];
  const workloadLabels = dashboard?.workloadLabels ?? [];
  const WORKLOAD_COLORS = ['#7c3aed', '#a855f7', '#c084fc', '#6366f1', '#818cf8'];

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <ViewTab label="Dashboard" active={view === 'dashboard'} onClick={() => setView('dashboard')} />
        <ViewTab label="Findings" active={view === 'findings'} onClick={() => setView('findings')} />
      </div>

      {view === 'dashboard' ? (
        <div className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-3 gap-4">
            <Stat value={totalCves} label="Total CVEs" />
            <Stat value={dashboard?.exploitableCves ?? 0} label="Exploitable CVEs" />
            <Stat value={dashboard?.totalFindings ?? 0} label="Total findings" />
          </div>
          <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
            <Panel title={`Unique CVEs by severity (${totalCves})`}>
              <div className="flex items-center gap-4">
                <div style={{ width: 130, height: 130 }} className="flex-shrink-0">
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart>
                      <Pie data={[
                        { name: 'Critical', value: critical, fill: SEV_COLOR.Critical },
                        { name: 'High', value: high, fill: SEV_COLOR.High },
                        { name: 'Other', value: other, fill: SEV_COLOR.Other },
                      ].filter((d) => d.value > 0)}
                        dataKey="value" nameKey="name" innerRadius={42} outerRadius={62} paddingAngle={2} stroke="none">
                        {[SEV_COLOR.Critical, SEV_COLOR.High, SEV_COLOR.Other].map((c, i) => <Cell key={i} fill={c} />)}
                      </Pie>
                      <Tooltip />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
                <div className="flex-1 space-y-2">
                  <Legend color={SEV_COLOR.Critical} label="Critical" value={critical} />
                  <Legend color={SEV_COLOR.High} label="High" value={high} />
                  <Legend color={SEV_COLOR.Other} label="Other" value={other} />
                </div>
              </div>
            </Panel>
            <Panel title="Unique CVEs by workload type">
              <div className="space-y-1.5">
                {(dashboard?.byWorkloadType ?? []).map((w) => (
                  <div key={w.type} className="flex items-center justify-between py-1.5 border-b border-gray-50 last:border-0">
                    <span className="text-xs text-gray-700">{workloadLabel(w.type)}</span>
                    <span className="text-xs font-bold text-gray-900">{w.count}</span>
                  </div>
                ))}
                {(dashboard?.byWorkloadType ?? []).length === 0 && <p className="py-4 text-center text-xs text-gray-400">No data.</p>}
              </div>
            </Panel>
            <Panel title="New CVEs (last 7 days)">
              <div className="flex items-center justify-around py-4">
                <FunnelStat label="Critical" value={newSev.CRITICAL ?? 0} color={SEV_COLOR.Critical} />
                <FunnelStat label="High" value={newSev.HIGH ?? 0} color={SEV_COLOR.High} />
                <FunnelStat label="Other" value={newSev.OTHER ?? 0} color={SEV_COLOR.Other} />
              </div>
            </Panel>
          </div>
          {/* Upwind-style cumulative trend charts */}
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <Panel title="Vulnerability findings by severity">
              <p className="text-[10px] text-gray-400 mb-2">Last 7 days</p>
              <div style={{ width: '100%', height: 200 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={severityOverTime} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                    <XAxis dataKey="date" tick={{ fontSize: 9, fill: '#9ca3af' }} axisLine={false} tickLine={false} />
                    <YAxis tick={{ fontSize: 9, fill: '#9ca3af' }} axisLine={false} tickLine={false} width={34} />
                    <Tooltip />
                    <ReLegend wrapperStyle={{ fontSize: 10 }} />
                    <Area type="monotone" dataKey="CRITICAL" name="Critical" stackId="1" stroke={SEV_COLOR.Critical} fill={SEV_COLOR.Critical} fillOpacity={0.85} />
                    <Area type="monotone" dataKey="HIGH" name="High" stackId="1" stroke={SEV_COLOR.High} fill={SEV_COLOR.High} fillOpacity={0.85} />
                    <Area type="monotone" dataKey="OTHER" name="Other" stackId="1" stroke={SEV_COLOR.Other} fill={SEV_COLOR.Other} fillOpacity={0.85} />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </Panel>
            <Panel title="Critical vulnerability findings by workload type">
              <p className="text-[10px] text-gray-400 mb-2">Last 7 days</p>
              <div style={{ width: '100%', height: 200 }}>
                {workloadLabels.length === 0 ? (
                  <p className="py-16 text-center text-xs text-gray-400">No critical findings.</p>
                ) : (
                  <ResponsiveContainer width="100%" height="100%">
                    <BarChart data={criticalWorkloadOverTime} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
                      <XAxis dataKey="date" tick={{ fontSize: 9, fill: '#9ca3af' }} axisLine={false} tickLine={false} />
                      <YAxis tick={{ fontSize: 9, fill: '#9ca3af' }} axisLine={false} tickLine={false} width={34} />
                      <Tooltip cursor={{ fill: '#f3f4f6' }} />
                      <ReLegend wrapperStyle={{ fontSize: 10 }} />
                      {workloadLabels.map((l, i) => (
                        <Bar key={l} dataKey={l} name={l} fill={WORKLOAD_COLORS[i % WORKLOAD_COLORS.length]}
                          radius={[2, 2, 0, 0]} maxBarSize={26} />
                      ))}
                    </BarChart>
                  </ResponsiveContainer>
                )}
              </div>
            </Panel>
          </div>

          <RuntimeFunnel stages={dashboard?.funnel ?? []} />

          <div className="grid grid-cols-1 gap-4">
            <Panel title="New CVEs over time">
              <p className="text-[10px] text-gray-400 mb-2">First seen · last 7 days</p>
              <div style={{ width: '100%', height: 120 }}>
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={trend} margin={{ top: 4, right: 4, left: 4, bottom: 0 }}>
                    <XAxis dataKey="label" tick={{ fontSize: 9, fill: '#9ca3af' }} axisLine={false} tickLine={false} />
                    <Tooltip cursor={{ fill: '#f3f4f6' }} />
                    <Bar dataKey="value" fill={SEV_COLOR.Critical} radius={[3, 3, 0, 0]} maxBarSize={26} />
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </Panel>
          </div>
        </div>
      ) : (
        <CveFindings byCve={byCve} isLoading={isLoading} onPage={onCvePage}
                     loadCveFindings={loadCveFindings} onOpenResource={open}
                     onSeverity={onCveSeverity} />
      )}
      {drawer}
    </div>
  );
};

/** Group-by-CVE table with expandable per-CVE drill-down of affected resources. */
const CveFindings: React.FC<{
  byCve: PageResp<CveGroupRow> | null;
  isLoading: boolean;
  onPage: (page: number) => void;
  loadCveFindings: (cveId: string) => Promise<CveResourceFinding[]>;
  onOpenResource: (resourceId: string) => void;
  onSeverity?: (severity: string) => void;
}> = ({ byCve, isLoading, onPage, loadCveFindings, onOpenResource, onSeverity }) => {
  const [expanded, setExpanded] = useState<string | null>(null);
  const [severity, setSeverity] = useState<string | null>(null);
  const [drill, setDrill] = useState<Record<string, CveResourceFinding[] | 'loading'>>({});
  const rows = byCve?.content ?? [];

  // Counts are CVE-level and computed server-side before filtering, so they match these rows.
  const severityGroups = useMemo(() => {
    const counts = byCve?.severityCounts ?? {};
    return SEVERITY_ORDER
      .filter((s) => (counts[s] ?? 0) > 0)
      .map((s) => ({ value: s, label: s.charAt(0) + s.slice(1).toLowerCase(), count: counts[s] }));
  }, [byCve]);

  const totalCveCount = useMemo(
    () => Object.values(byCve?.severityCounts ?? {}).reduce((a, b) => a + b, 0),
    [byCve]);

  const toggle = async (cveId: string) => {
    if (expanded === cveId) { setExpanded(null); return; }
    setExpanded(cveId);
    if (!drill[cveId]) {
      setDrill((d) => ({ ...d, [cveId]: 'loading' }));
      try {
        const res = await loadCveFindings(cveId);
        setDrill((d) => ({ ...d, [cveId]: res }));
      } catch (err) {
        console.error('[XamSecure] CVE drill-down failed:', err);
        setDrill((d) => ({ ...d, [cveId]: [] }));
      }
    }
  };

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm overflow-hidden">
      <div className="flex items-center justify-between px-5 py-3 border-b border-gray-200">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">CVEs</p>
        {byCve && <span className="text-[10px] text-gray-400">{byCve.totalElements} CVEs</span>}
      </div>
      {onSeverity && severityGroups.length > 1 && (
        <div className="px-5 py-3 border-b border-gray-100">
          <FilterChips
            options={severityGroups}
            active={severity}
            allCount={totalCveCount}
            tint={(v) => SEVERITY_TINT[v]}
            onChange={(v) => { setSeverity(v); setExpanded(null); onSeverity(v ?? ''); }}
          />
        </div>
      )}
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-[10px] uppercase text-gray-400 border-b border-gray-100">
              <th className="px-4 py-2.5 w-6"></th>
              <th className="px-3 py-2.5">CVE</th>
              <th className="px-3 py-2.5">Severity</th>
              <th className="px-3 py-2.5">CVSS</th>
              <th className="px-3 py-2.5">Risk</th>
              <th className="px-3 py-2.5">Findings</th>
              <th className="px-3 py-2.5">Affected resources</th>
              <th className="px-3 py-2.5">First seen</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((c) => (
              <React.Fragment key={c.cveId}>
                <tr className="border-b border-gray-50 hover:bg-gray-50 cursor-pointer" onClick={() => toggle(c.cveId)}>
                  <td className="px-4 py-3 text-gray-400">
                    <Icon icon={expanded === c.cveId ? 'mdi:chevron-down' : 'mdi:chevron-right'} width={16} />
                  </td>
                  <td className="px-3 py-3">
                    <span className="font-mono font-semibold text-gray-800">{c.cveId}</span>
                    {c.name && <span className="block text-[10px] text-gray-400 max-w-xs truncate">{c.name}</span>}
                  </td>
                  <td className="px-3 py-3"><SeverityChip severity={c.severity} /></td>
                  <td className="px-3 py-3 text-gray-600">{c.cvssScore ?? '—'}</td>
                  <td className="px-3 py-3">
                    <span className="flex items-center gap-1.5 text-gray-400">
                      {c.exploitable && (
                        <span title="Known exploit"><Icon icon="mdi:bug" width={13} className="text-gray-700" /></span>
                      )}
                      {c.epssScore != null && c.epssScore >= 0.5 && (
                        <span title="EPSS-prioritized"><Icon icon="mdi:trending-up" width={13} className="text-gray-700" /></span>
                      )}
                      {!c.exploitable && (c.epssScore == null || c.epssScore < 0.5) && <span className="text-gray-300">—</span>}
                    </span>
                  </td>
                  <td className="px-3 py-3 font-semibold text-gray-700">{c.findingsCount}</td>
                  <td className="px-3 py-3 font-semibold text-gray-700">{c.resourceCount}</td>
                  <td className="px-3 py-3 whitespace-nowrap text-gray-500">{fmtDate(c.firstSeen)}</td>
                </tr>
                {expanded === c.cveId && (
                  <tr className="bg-gray-50/60">
                    <td colSpan={8} className="px-8 py-3">
                      {drill[c.cveId] === 'loading' ? (
                        <p className="text-[11px] text-gray-400 py-2">Loading affected resources...</p>
                      ) : (
                        <CveDrill findings={(drill[c.cveId] as CveResourceFinding[]) ?? []}
                                  onOpenResource={onOpenResource} />
                      )}
                    </td>
                  </tr>
                )}
              </React.Fragment>
            ))}
            {rows.length === 0 && (
              <tr><td colSpan={8} className="px-5 py-12 text-center text-gray-400">
                {isLoading ? 'Loading CVEs...' : 'No vulnerabilities for the selected account.'}
              </td></tr>
            )}
          </tbody>
        </table>
      </div>
      {byCve && byCve.totalPages > 1 && (
        <div className="flex items-center justify-between px-5 py-3 border-t border-gray-200 text-xs text-gray-500">
          <span>Page {byCve.number + 1} of {byCve.totalPages} · {byCve.totalElements} CVEs</span>
          <div className="flex gap-2">
            <button disabled={byCve.number === 0} onClick={() => onPage(byCve.number - 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Prev</button>
            <button disabled={byCve.number + 1 >= byCve.totalPages} onClick={() => onPage(byCve.number + 1)}
                    className="px-3 py-1 border border-gray-200 rounded-lg disabled:opacity-40 hover:bg-gray-50">Next</button>
          </div>
        </div>
      )}
    </div>
  );
};

/** Paginated per-CVE affected-package table (a CVE can hit 100+ packages). */
const CveDrill: React.FC<{
  findings: CveResourceFinding[];
  onOpenResource: (resourceId: string) => void;
}> = ({ findings, onOpenResource }) => {
  const { page, setPage, pageItems, totalPages, total } = usePaged(findings, 20);
  return (
    <div className="border border-gray-200 rounded-lg overflow-hidden bg-white">
      <table className="w-full text-[11px]">
        <thead>
          <tr className="text-left text-[9px] uppercase text-gray-400 border-b border-gray-100">
            <th className="px-3 py-2">Resource</th>
            <th className="px-3 py-2">Type</th>
            <th className="px-3 py-2">Affected packages</th>
            <th className="px-3 py-2">In use</th>
            <th className="px-3 py-2">Fix available</th>
            <th className="px-3 py-2">Region</th>
            <th className="px-3 py-2">Last scan</th>
          </tr>
        </thead>
        <tbody>
          {pageItems.map((r, i) => (
            <tr key={i} className="border-b border-gray-50 last:border-0">
              <td className="px-3 py-2 text-gray-800 max-w-[160px]">
                <ResourceLink resourceId={r.resourceId} label={r.resourceName}
                              sub={r.cloudProvider} onOpen={onOpenResource} />
              </td>
              <td className="px-3 py-2 text-gray-600">{r.resourceType ?? '—'}</td>
              <td className="px-3 py-2 text-gray-700">
                {(r.packageCount ?? 0) > 1
                  ? <span className="font-medium">{r.packageCount} packages</span>
                  : (r.packageName ?? '—')}
              </td>
              <td className="px-3 py-2">
                <span className={`px-1.5 py-0.5 rounded text-[9px] font-bold ${
                  r.inUse ? 'bg-emerald-100 text-emerald-700' : 'bg-gray-100 text-gray-500'}`}>
                  {r.inUse ? 'Yes' : 'No'}
                </span>
              </td>
              <td className="px-3 py-2">
                {r.fixAvailable
                  ? <span className="text-emerald-600 font-medium">{r.fixedInVersion ?? 'Yes'}</span>
                  : <span className="text-gray-300">—</span>}
              </td>
              <td className="px-3 py-2 text-gray-600">{r.region ?? '—'}</td>
              <td className="px-3 py-2 text-gray-500 whitespace-nowrap">{fmtDate(r.lastScanTime)}</td>
            </tr>
          ))}
          {findings.length === 0 && (
            <tr><td colSpan={7} className="px-3 py-3 text-center text-gray-400">No affected resources.</td></tr>
          )}
        </tbody>
      </table>
      <Pager page={page} totalPages={totalPages} total={total} unit="resources" onPage={setPage} />
    </div>
  );
};

const FUNNEL_ICON: Record<string, string> = {
  'Total': 'mdi:bug-outline',
  'Package in use': 'mdi:package-variant-closed',
  'Internet exposure': 'mdi:web',
  'Known exploit': 'mdi:alert-octagon-outline',
  'Fix available': 'mdi:wrench-outline',
  'NIST critical sev': 'mdi:alpha-c-circle-outline',
  'EPSS-prioritized': 'mdi:chart-line-variant',
  'Functions in Use': 'mdi:function-variant',
};

/** Upwind-style horizontal runtime exploit funnel: stage headers + narrowing red ribbon. */
const RuntimeFunnel: React.FC<{ stages: FunnelStage[] }> = ({ stages }) => {
  if (stages.length === 0) {
    return (
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Runtime exploit funnel</p>
        <p className="py-8 text-center text-xs text-gray-400">No data.</p>
      </div>
    );
  }
  const W = 1000, H = 120, midY = H / 2, maxH = H * 0.92;
  const total = Math.max(1, stages[0].value);
  const n = stages.length;
  const x = (i: number) => (i / (n - 1)) * W;
  const h = (v: number) => Math.max(2, (Math.min(v, total) / total) * maxH);
  let d = `M ${x(0)} ${midY - h(stages[0].value) / 2}`;
  for (let i = 1; i < n; i++) d += ` L ${x(i)} ${midY - h(stages[i].value) / 2}`;
  d += ` L ${x(n - 1)} ${midY + h(stages[n - 1].value) / 2}`;
  for (let i = n - 2; i >= 0; i--) d += ` L ${x(i)} ${midY + h(stages[i].value) / 2}`;
  d += ' Z';

  return (
    <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-5">
      <div className="flex items-center justify-between mb-4">
        <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">Runtime exploit funnel</p>
        <div className="flex items-center gap-3 text-[10px] text-gray-400">
          <span>CVSS:</span>
          <LegendDot color="#dc2626" label="Critical" />
          <LegendDot color="#f97316" label="High" />
          <LegendDot color="#3b82f6" label="Other" />
        </div>
      </div>
      {/* stage headers */}
      <div className="flex justify-between gap-1 mb-1">
        {stages.map((s) => (
          <div key={s.label} className="flex-1 min-w-0 text-left">
            <div className="flex items-center gap-1 text-[9px] text-gray-500">
              <Icon icon={FUNNEL_ICON[s.label] ?? 'mdi:circle-small'} width={11} className="flex-shrink-0" />
              <span className="truncate">{s.label}</span>
            </div>
            <p className={`text-sm font-bold ${s.available ? 'text-gray-900' : 'text-gray-400'}`}>
              {s.value.toLocaleString()}
            </p>
          </div>
        ))}
      </div>
      {/* narrowing ribbon */}
      <svg viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" style={{ width: '100%', height: 110 }}>
        <defs>
          <linearGradient id="funnelGrad" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#f87171" />
            <stop offset="60%" stopColor="#ef4444" />
            <stop offset="100%" stopColor="#b91c1c" />
          </linearGradient>
        </defs>
        <path d={d} fill="url(#funnelGrad)" />
      </svg>
    </div>
  );
};

const LegendDot: React.FC<{ color: string; label: string }> = ({ color, label }) => (
  <span className="flex items-center gap-1">
    <span className="w-1.5 h-1.5 rounded-full" style={{ backgroundColor: color }} />{label}
  </span>
);

const Stat: React.FC<{ value: number; label: string }> = ({ value, label }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm p-6 text-center">
    <p className="text-4xl font-extrabold text-gray-900">{value.toLocaleString()}</p>
    <p className="mt-1 text-xs font-semibold text-gray-500">{label}</p>
  </div>
);
const FunnelStat: React.FC<{ label: string; value: number; color?: string }> = ({ label, value, color }) => (
  <div className="text-center">
    <p className="text-2xl font-extrabold" style={{ color: color ?? '#111827' }}>{value.toLocaleString()}</p>
    <p className="mt-1 text-[10px] font-semibold uppercase tracking-wide text-gray-400">{label}</p>
  </div>
);
const Legend: React.FC<{ color: string; label: string; value: number }> = ({ color, label, value }) => (
  <div className="flex items-center justify-between text-xs">
    <span className="flex items-center gap-1.5 text-gray-700">
      <span className="w-2 h-2 rounded-full" style={{ backgroundColor: color }} />{label}
    </span>
    <span className="font-bold text-gray-900">{value.toLocaleString()}</span>
  </div>
);
const Panel: React.FC<{ title: string; children: React.ReactNode }> = ({ title, children }) => (
  <div className="bg-white border border-gray-200 rounded-xl shadow-sm flex flex-col">
    <div className="px-5 py-3 border-b border-gray-200">
      <p className="text-xs font-semibold text-gray-500 uppercase tracking-wide">{title}</p>
    </div>
    <div className="p-5 flex-1">{children}</div>
  </div>
);
const ViewTab: React.FC<{ label: string; active: boolean; onClick: () => void }> = ({ label, active, onClick }) => (
  <button onClick={onClick}
          className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-colors ${
            active ? 'bg-gray-900 text-white' : 'text-gray-500 hover:bg-gray-100'}`}>
    {label}
  </button>
);

export default VulnerabilitiesTab;
