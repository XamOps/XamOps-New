import React, { useState, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import {
  useGcpDbOpsData,
  DbInstance, DbSavingsSummary, AgentQueryStat, AgentInstanceMetrics,
} from '../hooks/useGcpDbOpsData';
import DbInstanceDetailPage, { DrawerInstance } from '../components/DbOps/DbInstanceDetailDrawer';

// ── Helpers ───────────────────────────────────────────────────────────────────

const fmt$ = (n: number) =>
  `$${n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

function EncBadge({ val }: { val: boolean }) {
  return val
    ? <span className="text-green-600 dark:text-green-400"><i className="fas fa-lock mr-1" />Yes</span>
    : <span className="text-red-500"><i className="fas fa-unlock mr-1" />No</span>;
}

// ── Summary Cards ─────────────────────────────────────────────────────────────

function SummaryCards({ s }: { s: DbSavingsSummary }) {
  const cards = [
    { icon: 'fas fa-dollar-sign',  label: 'Monthly DB Spend',         value: fmt$(s.totalDbMonthlyCost),      accent: 'border-l-sky-500',    iconBg: 'from-sky-500 to-blue-600',       glow: 'rgba(14,165,233,0.25)' },
    { icon: 'fas fa-piggy-bank',   label: 'Savings Potential',         value: fmt$(s.totalPotentialSavings),   accent: 'border-l-emerald-500', iconBg: 'from-emerald-500 to-teal-600',  glow: 'rgba(16,185,129,0.25)' },
    { icon: 'fas fa-server',       label: 'Total Instances',           value: String(s.totalInstances),        accent: 'border-l-indigo-500',  iconBg: 'from-indigo-500 to-purple-600', glow: 'rgba(99,102,241,0.25)' },
    { icon: 'fas fa-moon',         label: 'Idle Instances',            value: `${s.idleInstances} (${fmt$(s.idleSavingsPotential)})`, accent: 'border-l-amber-500', iconBg: 'from-amber-500 to-orange-600', glow: 'rgba(245,158,11,0.25)' },
    { icon: 'fas fa-compress-alt', label: 'Right-sizing Candidates',   value: `${s.rightsizingCandidates} (${fmt$(s.rightsizingSavingsPotential)})`, accent: 'border-l-pink-500', iconBg: 'from-pink-500 to-rose-600', glow: 'rgba(236,72,153,0.25)' },
    { icon: 'fas fa-camera',       label: 'Backup Waste',              value: fmt$(s.snapshotWasteCost),       accent: 'border-l-violet-500',  iconBg: 'from-violet-500 to-purple-600', glow: 'rgba(139,92,246,0.25)' },
  ];
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4 mb-6">
      {cards.map((c, idx) => (
        <div
          key={c.label}
          className={`dataops-summary-card dataops-fade-in-up bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-200 dark:border-gray-700/50 border-l-4 ${c.accent} p-4 flex flex-col gap-2`}
          style={{ animationDelay: `${idx * 60}ms` }}
          onMouseEnter={e => { (e.currentTarget as HTMLDivElement).style.boxShadow = `0 12px 28px -4px ${c.glow}`; }}
          onMouseLeave={e => { (e.currentTarget as HTMLDivElement).style.boxShadow = ''; }}
        >
          <div className={`w-9 h-9 rounded-xl bg-gradient-to-br ${c.iconBg} flex items-center justify-center shadow-sm`}>
            <i className={`${c.icon} text-white text-xs`} />
          </div>
          <p className="text-[10px] text-gray-500 dark:text-gray-400 leading-tight font-semibold uppercase tracking-wider mt-0.5">{c.label}</p>
          <p className="text-sm font-bold text-gray-900 dark:text-gray-50 leading-tight">{c.value}</p>
        </div>
      ))}
    </div>
  );
}

// ── Inventory Tab ─────────────────────────────────────────────────────────────

function InventoryTab({
  inventory, agentStats, agentInstanceMetrics, onSelect,
}: {
  inventory: DbInstance[];
  agentStats: AgentQueryStat[];
  agentInstanceMetrics: AgentInstanceMetrics[];
  onSelect: (inst: DrawerInstance) => void;
}) {
  const [search, setSearch] = useState('');

  const agentHosts = new Map<string, AgentQueryStat[]>();
  agentStats.forEach(s => {
    if (!s.dbHost) return;
    const matchesCloudSql = inventory.some(i => i.endpoint && s.dbHost.startsWith(i.endpoint));
    if (!matchesCloudSql) {
      const list = agentHosts.get(s.dbHost) ?? [];
      list.push(s);
      agentHosts.set(s.dbHost, list);
    }
  });

  const allRows: DrawerInstance[] = [
    ...inventory.map(i => ({ instanceId: i.instanceId, source: 'rds' as const, rdsData: i, engine: i.engine })),
    ...Array.from(agentHosts.entries()).map(([host, stats]) => ({
      instanceId: host,
      source: 'agent' as const,
      agentHost: host,
      engine: stats[0]?.dbEngine ?? 'unknown',
    })),
  ];

  const filtered = allRows.filter(r =>
    r.instanceId.toLowerCase().includes(search.toLowerCase()) ||
    (r.engine ?? '').toLowerCase().includes(search.toLowerCase()) ||
    (r.rdsData?.region ?? '').toLowerCase().includes(search.toLowerCase())
  );

  return (
    <div>
      <div className="flex items-center gap-3 mb-4 flex-wrap">
        <div className="relative flex-1 min-w-[180px] max-w-xs">
          <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-sky-400 text-sm" />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search instances..."
            className="dataops-search-input w-full pl-9 pr-3 py-2 text-sm border border-gray-200 dark:border-gray-600 rounded-xl bg-white/80 dark:bg-gray-800/80 backdrop-blur-sm text-gray-800 dark:text-gray-200 focus:outline-none"
          />
        </div>
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-sky-50 dark:bg-sky-900/20 border border-sky-200 dark:border-sky-800/50 text-sky-700 dark:text-sky-400 text-xs font-semibold">
          <i className="fas fa-database text-[9px]" />
          {filtered.length} instances
        </span>
        {/* GCP badge */}
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800/50 text-blue-700 dark:text-blue-400 text-xs font-semibold">
          <i className="fab fa-google text-[9px]" />
          Cloud SQL
        </span>
        <button
          onClick={() => onSelect({ instanceId: '', source: 'agent', engine: 'mysql' })}
          className="dataops-connect-btn ml-auto flex items-center gap-2 px-4 py-2 text-white rounded-xl text-sm font-semibold"
        >
          <i className="fas fa-plus text-xs" /> Connect a Database
        </button>
      </div>
      <div className="dataops-fade-in-up bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 overflow-hidden" style={{ animationDelay: '120ms' }}>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 dark:border-gray-700/50" style={{ background: 'linear-gradient(90deg, rgba(14,165,233,0.04) 0%, rgba(37,99,235,0.04) 100%)' }}>
                {['Instance ID', 'Source', 'Engine', 'Tier', 'Region', 'Status', 'Storage (GB)', 'Encrypted', 'Backups', 'HA', 'Est. Cost/mo'].map(h => (
                  <th key={h} className="text-left px-4 py-3 text-[10px] font-bold text-gray-500 dark:text-gray-400 uppercase tracking-widest whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr>
                  <td colSpan={11}>
                    <div className="flex flex-col items-center justify-center py-16 gap-3">
                      <div className="dataops-empty-icon w-14 h-14 rounded-2xl flex items-center justify-center">
                        <i className="fas fa-database text-sky-500 text-xl" />
                      </div>
                      <p className="text-sm font-medium text-gray-500 dark:text-gray-400">No Cloud SQL instances found</p>
                    </div>
                  </td>
                </tr>
              ) : filtered.map(row => {
                const i = row.rdsData;
                const am = row.source === 'agent'
                  ? agentInstanceMetrics.find(m => m.dbHost === row.agentHost)
                  : undefined;
                const dbSizeGb = am?.dbSizeBytes ? (am.dbSizeBytes / 1073741824).toFixed(2) : null;
                const isHA = i?.multiAz === 'REGIONAL';
                return (
                  <tr
                    key={row.instanceId}
                    onClick={() => onSelect(row)}
                    className="dataops-table-row border-b border-gray-50 dark:border-gray-700/30 hover:bg-sky-50/30 dark:hover:bg-sky-900/10 cursor-pointer"
                  >
                    <td className="dataops-table-first-cell px-4 py-3 font-semibold text-sky-700 dark:text-sky-400 whitespace-nowrap">
                      <span className="hover:underline underline-offset-2">
                        {row.instanceId.split(':').pop() ?? row.instanceId}
                      </span>
                      {am?.dbName && <span className="ml-1.5 text-[10px] text-gray-400 font-normal">({am.dbName})</span>}
                    </td>
                    <td className="px-4 py-3">
                      {row.source === 'rds'
                        ? <span className="px-2 py-0.5 text-xs rounded-full bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400 font-semibold">Cloud SQL</span>
                        : <span className="px-2 py-0.5 text-xs rounded-full bg-violet-100 text-violet-700 dark:bg-violet-900/30 dark:text-violet-400 font-semibold">Agent</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-700 dark:text-gray-300 font-medium">{row.engine}</td>
                    <td className="px-4 py-3 text-gray-700 dark:text-gray-300 font-mono text-xs">
                      {i?.instanceClass ?? (am?.ec2InstanceType
                        ? <span title={am.ec2InstanceId ?? undefined} className="text-blue-600 dark:text-blue-400">{am.ec2InstanceType}</span>
                        : <span className="text-gray-400">—</span>)}
                    </td>
                    <td className="px-4 py-3 text-gray-700 dark:text-gray-300 whitespace-nowrap">
                      {(i?.region ?? am?.region)
                        ? <span className="inline-flex items-center gap-1"><i className="fas fa-location-dot text-[9px] text-sky-400" />{i?.region ?? am?.region}</span>
                        : <span className="text-gray-400">—</span>}
                    </td>
                    <td className="px-4 py-3">
                      {i ? (
                        <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${i.status === 'runnable' ? 'bg-emerald-100 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-400' : 'bg-yellow-100 text-yellow-700'}`}>
                          {i.status === 'runnable' && <i className="fas fa-circle text-[6px] mr-1 align-middle" />}{i.status}
                        </span>
                      ) : <span className="px-2 py-0.5 rounded-full text-xs bg-violet-100 text-violet-700 dark:bg-violet-900/30 dark:text-violet-400 font-semibold">live</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-700 dark:text-gray-300 text-center tabular-nums">{i?.allocatedStorage ?? (dbSizeGb ? dbSizeGb : <span className="text-gray-400">—</span>)}</td>
                    <td className="px-4 py-3">{i ? <EncBadge val={i.storageEncrypted} /> : <span className="text-gray-400">—</span>}</td>
                    <td className="px-4 py-3">
                      {i ? (i.backupEnabled
                        ? <span className="text-emerald-600 dark:text-emerald-400 font-medium"><i className="fas fa-check mr-1 text-[10px]" />{i.backupRetentionDays}d</span>
                        : <span className="text-red-500 font-medium"><i className="fas fa-times mr-1 text-[10px]" />Off</span>)
                      : <span className="text-gray-400">—</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-700 dark:text-gray-300">
                      {i?.multiAz != null
                        ? (isHA
                            ? <span className="text-emerald-600 dark:text-emerald-400"><i className="fas fa-check mr-1 text-[10px]" />HA</span>
                            : <span className="text-gray-400">Zonal</span>)
                        : <span className="text-gray-400">—</span>}
                    </td>
                    <td className="px-4 py-3 font-bold text-gray-800 dark:text-gray-100 whitespace-nowrap tabular-nums">
                      {i ? <span className="text-emerald-600 dark:text-emerald-400">{fmt$(i.estimatedMonthlyCost)}</span>
                        : (am?.ec2HourlyPrice && am.ec2HourlyPrice > 0
                            ? <span title="EC2 on-demand monthly cost" className="text-emerald-600 dark:text-emerald-400">{fmt$(am.ec2HourlyPrice * 730)}</span>
                            : <span className="text-gray-400">—</span>)}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}

// ── Loading Skeleton ──────────────────────────────────────────────────────────

function LoadingSkeleton() {
  return (
    <div className="space-y-4">
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="dataops-shimmer bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl p-4 h-[96px] border border-gray-100 dark:border-gray-700/50 border-l-4 border-l-sky-200 dark:border-l-sky-800/50">
            <div className="w-9 h-9 bg-sky-100/60 dark:bg-sky-900/20 rounded-xl mb-3" />
            <div className="h-2.5 bg-sky-100/60 dark:bg-sky-900/20 rounded-full w-2/3 mb-2" />
            <div className="h-4 bg-sky-100/60 dark:bg-sky-900/20 rounded-full w-1/2" />
          </div>
        ))}
      </div>
      <div className="dataops-shimmer bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50">
        <div className="p-4 border-b border-sky-100/40 dark:border-sky-800/20 flex gap-3">
          <div className="h-9 w-56 bg-sky-100/60 dark:bg-sky-900/20 rounded-xl" />
          <div className="h-9 w-28 bg-sky-100/60 dark:bg-sky-900/20 rounded-full ml-auto" />
        </div>
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="flex gap-4 px-4 py-3.5 border-b border-sky-50/50 dark:border-sky-800/10 last:border-0">
            <div className="h-4 bg-sky-100/60 dark:bg-sky-900/20 rounded-full w-48" />
            <div className="h-4 bg-sky-100/60 dark:bg-sky-900/20 rounded-full w-16" />
            <div className="h-4 bg-sky-100/60 dark:bg-sky-900/20 rounded-full w-20" />
            <div className="h-4 bg-sky-100/60 dark:bg-sky-900/20 rounded-full w-24 ml-auto" />
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

const GcpDataOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const { setTitle, setActions, clearActions } = useTopbar();
  const [selectedInstance, setSelectedInstance] = useState<DrawerInstance | null>(null);
  const [copiedEndpoint, setCopiedEndpoint] = useState(false);
  const [lastRefreshed, setLastRefreshed] = useState<Date>(new Date());
  const [instanceHealth, setInstanceHealth] = useState<{ score: number; status: 'healthy' | 'warning' | 'critical'; issues: string[] } | null>(null);

  const handleHealthChange = useCallback((h: { score: number; status: 'healthy' | 'warning' | 'critical'; issues: string[] }) => {
    setInstanceHealth(h);
  }, []);

  const projectId = selectedAccountIds[0] ?? null;
  const {
    summary, inventory, metrics, costs, idle, rightsizing, snapshots, security, slowQueries,
    agentQueryStats, agentInstanceMetrics,
    loading, error, refresh, fetchInstanceTimeseries, fetchAgentTimeseries, fetchAgentTableSizes,
    fetchAgentVacuumStats, fetchAgentUserActivity, fetchAgentIndexUsage, fetchAgentTableActivity,
  } = useGcpDbOpsData(projectId);

  React.useEffect(() => {
    setTitle(selectedInstance ? (selectedInstance.instanceId || 'Connect Database') : 'DbOps');
    return () => setTitle('');
  }, [setTitle, selectedInstance]);

  React.useEffect(() => {
    if (selectedInstance) {
      const rd = selectedInstance.rdsData;
      const engine = rd?.engine ?? selectedInstance.engine ?? 'unknown';
      const instanceIdle = idle.find(i => i.instanceId === selectedInstance.instanceId);
      const endpoint = rd?.endpoint ?? selectedInstance.agentHost ?? '';
      const h = instanceHealth;

      const accentRgb = !h ? '99,102,241'
        : h.status === 'healthy'  ? '16,185,129'
        : h.status === 'warning'  ? '245,158,11'
        : '239,68,68';

      setActions(
        <div className="flex items-center gap-2">

          {/* ── Back ── */}
          <button
            onClick={() => { setSelectedInstance(null); setInstanceHealth(null); }}
            className="group flex items-center gap-1.5 h-8 px-3 rounded-lg text-[11px] font-medium
              bg-white/60 dark:bg-white/5 border border-gray-200/80 dark:border-white/10
              text-gray-500 dark:text-slate-400
              hover:text-indigo-600 dark:hover:text-indigo-400
              hover:border-indigo-300/60 dark:hover:border-indigo-500/40
              hover:bg-indigo-50/60 dark:hover:bg-indigo-500/10
              transition-all duration-200 backdrop-blur-sm"
          >
            <i className="fas fa-chevron-left text-[9px] transition-transform duration-200 group-hover:-translate-x-0.5" />
            Back
          </button>

          {/* ── Instance identity card ── */}
          <div
            className="flex items-center gap-2 h-9 px-3 rounded-xl backdrop-blur-sm
              bg-white/70 dark:bg-slate-900/60 border border-gray-200/60 dark:border-white/10"
            style={{ boxShadow: `0 0 0 1px rgba(${accentRgb},0.12), 0 0 14px rgba(${accentRgb},0.07)` }}
          >
            <div className={`w-5 h-5 rounded-md flex items-center justify-center text-white text-[9px] font-bold shrink-0 shadow-sm
              ${selectedInstance.source === 'rds'
                ? 'bg-gradient-to-br from-blue-500 to-cyan-600'
                : 'bg-gradient-to-br from-indigo-500 to-purple-600'}`}>
              <i className={`fas ${selectedInstance.source === 'rds' ? 'fa-database' : 'fa-server'} text-[8px]`} />
            </div>

            <span className="text-[10px] text-gray-500 dark:text-slate-400 font-medium">{engine}</span>

            {selectedInstance.source === 'rds'
              ? <span className="px-1.5 py-px text-[9px] rounded font-bold bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400 tracking-wide">Cloud SQL</span>
              : <span className="px-1.5 py-px text-[9px] rounded font-bold bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400 tracking-wide">Agent</span>}

            {rd?.status && (
              <span className="flex items-center gap-1 text-[9px] font-medium text-emerald-600 dark:text-emerald-400">
                <span className="relative flex h-1.5 w-1.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-60" />
                  <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-emerald-500" />
                </span>
                {rd.status}
              </span>
            )}

            {instanceIdle && (
              <span className="px-1.5 py-px text-[9px] rounded font-semibold bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400">
                <i className="fas fa-moon mr-0.5 text-[8px]" />Idle
              </span>
            )}
          </div>

          {/* ── Health gauge ── */}
          {h && (
            <div
              className={`flex items-center gap-2 h-9 px-3 rounded-xl backdrop-blur-sm
                ${h.status === 'healthy'
                  ? 'bg-emerald-50/80 dark:bg-emerald-900/20 border border-emerald-200/70 dark:border-emerald-500/25'
                  : h.status === 'warning'
                  ? 'bg-amber-50/80 dark:bg-amber-900/20 border border-amber-200/70 dark:border-amber-500/25'
                  : 'bg-red-50/80 dark:bg-red-900/20 border border-red-200/70 dark:border-red-500/25'}`}
              style={{ boxShadow: `0 0 16px rgba(${accentRgb},0.13)` }}
              title={h.issues.length > 0 ? h.issues.join(' · ') : 'No issues detected'}
            >
              <div className="relative w-7 h-7 shrink-0">
                <svg className="w-7 h-7 -rotate-90" viewBox="0 0 28 28">
                  <circle cx="14" cy="14" r="10" fill="none" strokeWidth="2.5" stroke="currentColor"
                    className={h.status === 'healthy' ? 'text-emerald-200 dark:text-emerald-900/60'
                      : h.status === 'warning' ? 'text-amber-200 dark:text-amber-900/60'
                      : 'text-red-200 dark:text-red-900/60'} />
                  <circle cx="14" cy="14" r="10" fill="none" strokeWidth="2.5" stroke="currentColor"
                    strokeDasharray={`${2 * Math.PI * 10}`}
                    strokeDashoffset={`${2 * Math.PI * 10 * (1 - h.score / 100)}`}
                    strokeLinecap="round"
                    className={h.status === 'healthy' ? 'text-emerald-500' : h.status === 'warning' ? 'text-amber-500' : 'text-red-500'}
                    style={{ transition: 'stroke-dashoffset 1s ease' }}
                  />
                </svg>
                <div className="absolute inset-0 flex items-center justify-center">
                  <span className={`text-[9px] font-black leading-none tabular-nums
                    ${h.status === 'healthy' ? 'text-emerald-700 dark:text-emerald-300'
                      : h.status === 'warning' ? 'text-amber-700 dark:text-amber-300'
                      : 'text-red-700 dark:text-red-300'}`}>{h.score}</span>
                </div>
              </div>
              <div className="flex flex-col leading-none">
                <span className={`text-[10px] font-bold
                  ${h.status === 'healthy' ? 'text-emerald-700 dark:text-emerald-300'
                    : h.status === 'warning' ? 'text-amber-700 dark:text-amber-300'
                    : 'text-red-700 dark:text-red-300'}`}>
                  {h.status === 'healthy' ? 'Healthy' : h.status === 'warning' ? 'Warning' : 'Critical'}
                </span>
                <span className="text-[8px] text-gray-400 dark:text-slate-500 mt-0.5">Health Score</span>
              </div>
            </div>
          )}

          {/* ── Issue chips ── */}
          {h && h.issues.length > 0 && (
            <div className="hidden md:flex items-center gap-1.5">
              {h.issues.slice(0, 2).map((issue, i) => (
                <span key={i} className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[9px] font-semibold backdrop-blur-sm
                  ${h.status === 'critical'
                    ? 'bg-red-50 dark:bg-red-900/25 border border-red-200/70 dark:border-red-500/25 text-red-600 dark:text-red-400'
                    : 'bg-amber-50 dark:bg-amber-900/25 border border-amber-200/70 dark:border-amber-500/25 text-amber-600 dark:text-amber-400'}`}>
                  <i className="fas fa-circle-dot text-[7px]" />
                  {issue}
                </span>
              ))}
              {h.issues.length > 2 && (
                <span className="text-[9px] text-gray-400 dark:text-slate-500 font-medium">+{h.issues.length - 2}</span>
              )}
            </div>
          )}

          {/* ── Meta strip ── */}
          <div className="hidden lg:flex items-center gap-1.5 text-[10px] text-gray-400 dark:text-slate-500 font-medium">
            {rd?.instanceClass && (
              <span className="flex items-center gap-1">
                <i className="fas fa-microchip text-[8px] text-gray-300 dark:text-slate-600" />{rd.instanceClass}
              </span>
            )}
            {rd?.region && (
              <>
                <span className="text-gray-200 dark:text-slate-700">·</span>
                <span className="flex items-center gap-1">
                  <i className="fas fa-location-dot text-[8px] text-gray-300 dark:text-slate-600" />{rd.region}
                </span>
              </>
            )}
            {rd?.estimatedMonthlyCost !== undefined && (
              <>
                <span className="text-gray-200 dark:text-slate-700">·</span>
                <span className="font-bold text-emerald-600 dark:text-emerald-400 tabular-nums">
                  {fmt$(rd.estimatedMonthlyCost)}/mo
                </span>
              </>
            )}
          </div>

          <span className="w-px h-5 bg-gray-200 dark:bg-slate-700/80 mx-0.5" />

          {/* ── Copy endpoint ── */}
          {endpoint && (
            <button
              onClick={() => {
                navigator.clipboard.writeText(endpoint);
                setCopiedEndpoint(true);
                setTimeout(() => setCopiedEndpoint(false), 2000);
              }}
              className={`group flex items-center gap-1.5 h-8 px-3 rounded-lg text-[11px] font-medium
                backdrop-blur-sm transition-all duration-200
                ${copiedEndpoint
                  ? 'bg-emerald-50 dark:bg-emerald-900/25 border border-emerald-300/70 dark:border-emerald-500/35 text-emerald-600 dark:text-emerald-400'
                  : 'bg-white/60 dark:bg-slate-800/60 border border-gray-200/70 dark:border-white/10 text-gray-600 dark:text-slate-300 hover:bg-indigo-50/60 dark:hover:bg-indigo-500/10 hover:border-indigo-300/60 dark:hover:border-indigo-500/40 hover:text-indigo-600 dark:hover:text-indigo-400'}`}
              title={`Copy: ${endpoint}`}
            >
              <i className={`fas ${copiedEndpoint ? 'fa-check' : 'fa-copy'} text-[10px]`} />
              <span>{copiedEndpoint ? 'Copied!' : 'Copy'}</span>
            </button>
          )}

          {/* ── Refresh ── */}
          <button
            onClick={() => { refresh(); setLastRefreshed(new Date()); }}
            disabled={loading}
            className="group flex items-center justify-center w-8 h-8 rounded-lg backdrop-blur-sm
              bg-white/60 dark:bg-slate-800/60 border border-gray-200/70 dark:border-white/10
              text-gray-500 dark:text-slate-400
              hover:text-indigo-600 dark:hover:text-indigo-400
              hover:bg-indigo-50/60 dark:hover:bg-indigo-500/10
              hover:border-indigo-300/60 dark:hover:border-indigo-500/40
              transition-all duration-200 disabled:opacity-40"
            title={`Last updated: ${lastRefreshed.toLocaleTimeString()}`}
          >
            <i className={`fas fa-rotate-right text-[11px] ${loading ? 'animate-spin' : 'group-hover:rotate-180'} transition-transform duration-500`} />
          </button>

        </div>
      );
    } else {
      setInstanceHealth(null);
      setActions(
        <div className="flex items-center gap-2">
          {/* GCP badge */}
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-700/50 text-blue-700 dark:text-blue-400 text-xs font-semibold">
            <i className="fab fa-google text-[9px]" />
            GCP
          </span>
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-amber-50 dark:bg-amber-900/20 border border-amber-300 dark:border-amber-700/50 text-amber-700 dark:text-amber-400 text-xs font-semibold">
            <i className="fas fa-flask text-[9px]" />
            Beta
          </span>
          <button
            onClick={refresh}
            disabled={loading}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-white dark:bg-gray-800 border border-gray-200 dark:border-gray-600 text-gray-800 dark:text-gray-300 rounded-lg text-xs font-medium hover:bg-gray-50 dark:hover:bg-gray-700 transition-colors disabled:opacity-50"
          >
            <i className={`fas fa-sync-alt text-[10px] ${loading ? 'animate-spin' : ''}`} />
            Refresh
          </button>
        </div>
      );
    }
    return () => clearActions();
  }, [setActions, clearActions, selectedInstance, projectId, summary, loading, refresh, idle, instanceHealth, copiedEndpoint, lastRefreshed]);

  return (
    <main className="dataops-page flex-1 flex flex-col min-h-screen bg-transparent">
      <div className="flex-1 p-6">

        {/* No account selected */}
        {!projectId && (
          <div className="dataops-fade-in-up text-center py-24 bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50">
            <div className="dataops-float-animation w-20 h-20 rounded-2xl flex items-center justify-center mx-auto mb-6 bg-gradient-to-br from-blue-500/10 to-cyan-600/10 border border-blue-200/40 dark:border-blue-700/30">
              <i className="fas fa-database text-blue-500 text-3xl" />
            </div>
            <h3 className="dataops-gradient-text text-xl font-bold mb-2">Select a GCP Project</h3>
            <p className="text-sm text-gray-500 dark:text-gray-400 max-w-sm mx-auto leading-relaxed">
              Choose a GCP project from the sidebar to view Cloud SQL database optimisation data.
            </p>
          </div>
        )}

        {/* Error */}
        {projectId && error && (
          <div className="dataops-fade-in-up mb-4 flex items-center gap-3 p-4 bg-red-50/90 dark:bg-red-900/20 backdrop-blur-sm border border-red-200 dark:border-red-800/50 rounded-xl text-red-700 dark:text-red-400 text-sm shadow-sm">
            <div className="w-7 h-7 rounded-lg bg-red-100 dark:bg-red-900/30 flex items-center justify-center flex-shrink-0">
              <i className="fas fa-exclamation-circle text-red-500 text-xs" />
            </div>
            <span className="flex-1">{error}</span>
            <button onClick={refresh} className="px-3 py-1 rounded-lg bg-red-100 dark:bg-red-900/30 text-red-700 dark:text-red-400 text-xs font-semibold hover:bg-red-200 dark:hover:bg-red-900/50 transition-colors">Retry</button>
          </div>
        )}

        {/* Loading */}
        {projectId && loading && <LoadingSkeleton />}

        {/* Instance detail — full page */}
        {projectId && !loading && selectedInstance && (
          <DbInstanceDetailPage
            instance={selectedInstance}
            metrics={metrics[selectedInstance.instanceId]}
            agentStats={agentQueryStats}
            agentInstanceMetrics={agentInstanceMetrics}
            slowQueries={slowQueries?.queries ?? []}
            idle={idle}
            rightsizing={rightsizing}
            security={security}
            allSnapshots={snapshots}
            costs={costs}
            cloudProvider="gcp"
            fetchTimeseries={fetchInstanceTimeseries}
            fetchAgentTimeseries={fetchAgentTimeseries}
            fetchAgentTableSizes={fetchAgentTableSizes}
            fetchAgentVacuumStats={fetchAgentVacuumStats}
            fetchAgentUserActivity={fetchAgentUserActivity}
            fetchAgentIndexUsage={fetchAgentIndexUsage}
            fetchAgentTableActivity={fetchAgentTableActivity}
            onConnected={async (inst) => { await refresh(); setSelectedInstance(inst); }}
            onHealthChange={handleHealthChange}
          />
        )}

        {/* Inventory */}
        {projectId && !loading && !selectedInstance && (
          <>
            {(() => {
              const agentOnlyHosts = new Set<string>();
              agentQueryStats.forEach(s => {
                if (!s.dbHost) return;
                const matchesCloudSql = inventory.some(i => i.endpoint && s.dbHost.startsWith(i.endpoint));
                if (!matchesCloudSql) agentOnlyHosts.add(s.dbHost);
              });
              const agentOnlyCost = Array.from(agentOnlyHosts).reduce((sum, host) => {
                const am = agentInstanceMetrics.find(m => m.dbHost === host);
                return sum + (am?.ec2HourlyPrice ? am.ec2HourlyPrice * 730 : 0);
              }, 0);
              const combinedSummary = summary
                ? { ...summary, totalInstances: summary.totalInstances + agentOnlyHosts.size, totalDbMonthlyCost: summary.totalDbMonthlyCost + agentOnlyCost }
                : agentOnlyHosts.size > 0
                  ? { totalDbMonthlyCost: agentOnlyCost, totalPotentialSavings: 0, totalInstances: agentOnlyHosts.size, idleInstances: 0, idleSavingsPotential: 0, rightsizingCandidates: 0, rightsizingSavingsPotential: 0, snapshotWasteCost: 0, savingsPercentage: 0 }
                  : null;
              return combinedSummary && <SummaryCards s={combinedSummary} />;
            })()}
            <InventoryTab inventory={inventory} agentStats={agentQueryStats} agentInstanceMetrics={agentInstanceMetrics} onSelect={setSelectedInstance} />
          </>
        )}

      </div>
    </main>
  );
};

export default GcpDataOpsPage;
