import React, { useState, useCallback } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import {
  useAzureDbOpsData,
  DbInstance, DbSavingsSummary, AgentQueryStat, AgentInstanceMetrics,
} from '../hooks/useAzureDbOpsData';
import DbInstanceDetailPage, { DrawerInstance } from '../components/DbOps/DbInstanceDetailDrawer';

// ── Helpers ───────────────────────────────────────────────────────────────────

const fmt$ = (n: number) =>
  `$${n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

function EncBadge({ val }: { val: boolean }) {
  return val
    ? <span className="text-green-600"><Icon icon="mdi:lock-outline" width={13} className="inline mr-1" />Yes</span>
    : <span className="text-red-500"><Icon icon="mdi:lock-open-outline" width={13} className="inline mr-1" />No</span>;
}

// ── Summary Cards ─────────────────────────────────────────────────────────────

function SummaryCards({ s }: { s: DbSavingsSummary }) {
  const cards = [
    { label: 'Monthly DB Spend',       value: fmt$(s.totalDbMonthlyCost) },
    { label: 'Savings Potential',      value: fmt$(s.totalPotentialSavings) },
    { label: 'Total Instances',        value: String(s.totalInstances) },
    { label: 'Idle Instances',         value: `${s.idleInstances}`, sub: fmt$(s.idleSavingsPotential) + ' savings' },
    { label: 'Right-sizing Candidates',value: `${s.rightsizingCandidates}`, sub: fmt$(s.rightsizingSavingsPotential) + ' savings' },
    { label: 'Backup Waste',           value: fmt$(s.snapshotWasteCost) },
  ];
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4 mb-6">
      {cards.map((c, i) => (
        <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-5 relative overflow-hidden card-hover-lift" style={{ animation: `fadeInUp 0.4s cubic-bezier(0.22,1,0.36,1) ${i * 0.06}s both` }}>
          <p className="text-[11px] font-semibold uppercase tracking-[0.08em] text-[rgba(0,0,0,0.45)] mb-2 leading-tight">{c.label}</p>
          <p className="text-[22px] font-bold text-gray-900 leading-tight tabular-nums">{c.value}</p>
          {c.sub && <p className="text-[11px] text-gray-400 mt-1">{c.sub}</p>}
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
    const matchesAzure = inventory.some(i => i.endpoint && s.dbHost.startsWith(i.endpoint));
    if (!matchesAzure) {
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
          <Icon icon="mdi:magnify" width={16} color="#38bdf8" className="absolute left-3 top-1/2 -translate-y-1/2" />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search instances..."
            className="w-full pl-9 pr-3 py-2 text-sm border border-gray-200 rounded-xl bg-white text-gray-800 focus:outline-none focus:border-[#cc785c]"
          />
        </div>
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#efe9de] border border-[#e6dfd8] text-[#3d3d3a] text-xs font-semibold">
          <Icon icon="mdi:database-outline" width={12} />
          {filtered.length} instances
        </span>
        {/* Azure badge */}
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#efe9de] border border-[#e6dfd8] text-[#3d3d3a] text-xs font-semibold">
          <Icon icon="mdi:microsoft-azure" width={12} color="#cc785c" />
          Azure SQL / PostgreSQL / MySQL
        </span>
        <button
          onClick={() => onSelect({ instanceId: '', source: 'agent', engine: 'mysql' })}
          className="ml-auto flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-xl text-sm font-semibold transition-colors"
        >
          <Icon icon="mdi:plus" width={14} /> Connect a Database
        </button>
      </div>
      <div className="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-[#e6dfd8] bg-gray-50">
                {['Instance ID', 'Source', 'Engine', 'Tier', 'Region', 'Status', 'Storage (GB)', 'Encrypted', 'Backups', 'HA', 'Est. Cost/mo'].map(h => (
                  <th key={h} className="text-left px-4 py-3 text-[10px] font-bold text-[#6c6a64] uppercase tracking-widest whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr>
                  <td colSpan={11}>
                    <div className="flex flex-col items-center justify-center py-16 gap-3">
                      <div className="w-14 h-14 rounded-xl bg-sky-50 border border-sky-100 flex items-center justify-center">
                        <Icon icon="mdi:database-outline" width={28} color="#0284c7" />
                      </div>
                      <p className="text-sm font-medium text-gray-500">No Azure SQL / PostgreSQL / MySQL instances found</p>
                    </div>
                  </td>
                </tr>
              ) : filtered.map((row, idx) => {
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
                    className="border-b border-[#ebe6df] hover:bg-[#faf9f5] cursor-pointer"
                    style={{ animation: `fadeInUp 0.3s ease ${Math.min(idx * 0.04, 0.4)}s both` }}
                  >
                    <td className="px-4 py-3 font-semibold text-[#cc785c] whitespace-nowrap">
                      <span className="hover:underline underline-offset-2">
                        {row.instanceId.split(':').pop() ?? row.instanceId}
                      </span>
                      {am?.dbName && <span className="ml-1.5 text-[10px] text-gray-400 font-normal">({am.dbName})</span>}
                    </td>
                    <td className="px-4 py-3">
                      {row.source === 'rds'
                        ? <span className="px-2 py-0.5 text-xs rounded-full bg-[#efe9de] text-[#3d3d3a] font-semibold">Azure SQL</span>
                        : <span className="px-2 py-0.5 text-xs rounded-full bg-violet-100 text-violet-700 font-semibold">Agent</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-700 font-medium">{row.engine}</td>
                    <td className="px-4 py-3 text-gray-700 font-mono text-xs">
                      {i?.instanceClass ?? (am?.ec2InstanceType
                        ? <span title={am.ec2InstanceId ?? undefined} className="text-[#cc785c]">{am.ec2InstanceType}</span>
                        : <span className="text-gray-400">—</span>)}
                    </td>
                    <td className="px-4 py-3 text-gray-700 whitespace-nowrap">
                      {(i?.region ?? am?.region)
                        ? <span className="inline-flex items-center gap-1"><Icon icon="mdi:map-marker-outline" width={12} color="#38bdf8" className="inline" />{i?.region ?? am?.region}</span>
                        : <span className="text-gray-400">—</span>}
                    </td>
                    <td className="px-4 py-3">
                      {i ? (
                        <span className={`px-2 py-0.5 rounded-full text-xs font-semibold ${i.status === 'runnable' ? 'bg-emerald-100 text-emerald-700' : 'bg-yellow-100 text-yellow-700'}`}>
                          {i.status === 'runnable' && <span className="w-1.5 h-1.5 rounded-full bg-emerald-500 inline-block mr-1 align-middle" />}{i.status}
                        </span>
                      ) : <span className="px-2 py-0.5 rounded-full text-xs bg-violet-100 text-violet-700 font-semibold">live</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-700 text-center tabular-nums">{i?.allocatedStorage ?? (dbSizeGb ? dbSizeGb : <span className="text-gray-400">—</span>)}</td>
                    <td className="px-4 py-3">{i ? <EncBadge val={i.storageEncrypted} /> : <span className="text-gray-400">—</span>}</td>
                    <td className="px-4 py-3">
                      {i ? (i.backupEnabled
                        ? <span className="text-emerald-600 font-medium"><Icon icon="mdi:check" width={12} className="inline mr-1" />{i.backupRetentionDays}d</span>
                        : <span className="text-red-500 font-medium"><Icon icon="mdi:close" width={12} className="inline mr-1" />Off</span>)
                      : <span className="text-gray-400">—</span>}
                    </td>
                    <td className="px-4 py-3 text-gray-700">
                      {i?.multiAz != null
                        ? (isHA
                            ? <span className="text-emerald-600"><Icon icon="mdi:check" width={12} className="inline mr-1" />HA</span>
                            : <span className="text-gray-400">Zonal</span>)
                        : <span className="text-gray-400">—</span>}
                    </td>
                    <td className="px-4 py-3 font-bold text-gray-800 whitespace-nowrap tabular-nums">
                      {i ? <span className="text-emerald-600">{fmt$(i.estimatedMonthlyCost)}</span>
                        : (am?.ec2HourlyPrice && am.ec2HourlyPrice > 0
                            ? <span title="VM on-demand monthly cost" className="text-emerald-600">{fmt$(am.ec2HourlyPrice * 730)}</span>
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
          <div key={i} className="animate-pulse bg-gray-200/60 rounded-xl p-4 h-[96px] border border-gray-100 border-l-4 border-l-sky-200">
            <div className="w-9 h-9 bg-gray-300/60 rounded-xl mb-3" />
            <div className="h-2.5 bg-gray-300/60 rounded-full w-2/3 mb-2" />
            <div className="h-4 bg-gray-300/60 rounded-full w-1/2" />
          </div>
        ))}
      </div>
      <div className="animate-pulse bg-gray-200/60 rounded-xl border border-gray-100">
        <div className="p-4 border-b border-gray-200/40 flex gap-3">
          <div className="h-9 w-56 bg-gray-300/60 rounded-xl" />
          <div className="h-9 w-28 bg-gray-300/60 rounded-full ml-auto" />
        </div>
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="flex gap-4 px-4 py-3.5 border-b border-gray-100/50 last:border-0">
            <div className="h-4 bg-gray-300/60 rounded-full w-48" />
            <div className="h-4 bg-gray-300/60 rounded-full w-16" />
            <div className="h-4 bg-gray-300/60 rounded-full w-20" />
            <div className="h-4 bg-gray-300/60 rounded-full w-24 ml-auto" />
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

const AzureDataOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const { setTitle, setActions, clearActions } = useTopbar();
  const [selectedInstance, setSelectedInstance] = useState<DrawerInstance | null>(null);
  const [copiedEndpoint, setCopiedEndpoint] = useState(false);
  const [lastRefreshed, setLastRefreshed] = useState<Date>(new Date());
  const [instanceHealth, setInstanceHealth] = useState<{ score: number; status: 'healthy' | 'warning' | 'critical'; issues: string[] } | null>(null);

  const handleHealthChange = useCallback((h: { score: number; status: 'healthy' | 'warning' | 'critical'; issues: string[] }) => {
    setInstanceHealth(h);
  }, []);

  const subscriptionId = selectedAccountIds[0] ?? null;
  const {
    summary, inventory, metrics, costs, idle, rightsizing, snapshots, security, slowQueries,
    agentQueryStats, agentInstanceMetrics,
    loading, error, refresh, fetchInstanceTimeseries, fetchAgentTimeseries, fetchAgentTableSizes,
    fetchAgentVacuumStats, fetchAgentUserActivity, fetchAgentIndexUsage, fetchAgentTableActivity,
  } = useAzureDbOpsData(subscriptionId);

  React.useEffect(() => {
    setTitle(selectedInstance ? (selectedInstance.instanceId || 'Connect Database') : 'Azure Database Ops');
    return () => setTitle('');
  }, [setTitle, selectedInstance]);

  React.useEffect(() => {
    if (selectedInstance) {
      const rd = selectedInstance.rdsData;
      const engine = rd?.engine ?? selectedInstance.engine ?? 'unknown';
      const instanceIdle = idle.find(i => i.instanceId === selectedInstance.instanceId);
      const endpoint = rd?.endpoint ?? selectedInstance.agentHost ?? '';
      const h = instanceHealth;

      const accentRgb = !h ? '204,120,92'
        : h.status === 'healthy'  ? '16,185,129'
        : h.status === 'warning'  ? '245,158,11'
        : '239,68,68';

      setActions(
        <div className="flex items-center gap-2">

          {/* ── Back ── */}
          <button
            onClick={() => { setSelectedInstance(null); setInstanceHealth(null); }}
            className="group flex items-center gap-1.5 h-8 px-3 rounded-lg text-[11px] font-medium
              bg-white border border-[#e6dfd8]
              text-[#6c6a64]
              hover:text-[#cc785c]
              hover:border-[#cc785c]/60
              hover:bg-[#faf9f5]
              transition-all duration-200"
          >
            <Icon icon="mdi:chevron-left" width={14} />
            Back
          </button>

          {/* ── Instance identity card ── */}
          <div
            className="flex items-center gap-2 h-9 px-3 rounded-xl
              bg-white border border-[#e6dfd8]"
            style={{ boxShadow: `0 0 0 1px rgba(${accentRgb},0.12), 0 0 14px rgba(${accentRgb},0.07)` }}
          >
            <div className={`w-5 h-5 rounded-md flex items-center justify-center text-[9px] font-bold shrink-0 shadow-sm
              ${selectedInstance.source === 'rds'
                ? 'bg-orange-50 border border-orange-100'
                : 'bg-indigo-50 border border-indigo-100'}`}>
              <Icon icon={selectedInstance.source === 'rds' ? 'mdi:database-outline' : 'mdi:server-outline'} width={10} color={selectedInstance.source === 'rds' ? '#ea580c' : '#4F46E5'} />
            </div>

            <span className="text-[10px] text-[#6c6a64] font-medium">{engine}</span>

            {selectedInstance.source === 'rds'
              ? <span className="px-1.5 py-px text-[9px] rounded font-bold bg-[#efe9de] text-[#3d3d3a] tracking-wide">Azure SQL</span>
              : <span className="px-1.5 py-px text-[9px] rounded font-bold bg-purple-100 text-purple-700 tracking-wide">Agent</span>}

            {rd?.status && (
              <span className="flex items-center gap-1 text-[9px] font-medium text-emerald-600">
                <span className="relative flex h-1.5 w-1.5">
                  <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-60" />
                  <span className="relative inline-flex rounded-full h-1.5 w-1.5 bg-emerald-500" />
                </span>
                {rd.status}
              </span>
            )}

            {instanceIdle && (
              <span className="px-1.5 py-px text-[9px] rounded font-semibold bg-amber-100 text-amber-700">
                <Icon icon="mdi:moon-waning-crescent" width={10} className="inline mr-0.5" />Idle
              </span>
            )}
          </div>

          {/* ── Health gauge ── */}
          {h && (
            <div
              className={`flex items-center gap-2 h-9 px-3 rounded-xl
                ${h.status === 'healthy'
                  ? 'bg-emerald-50 border border-emerald-200'
                  : h.status === 'warning'
                  ? 'bg-amber-50 border border-amber-200'
                  : 'bg-red-50 border border-red-200'}`}
              style={{ boxShadow: `0 0 16px rgba(${accentRgb},0.13)` }}
              title={h.issues.length > 0 ? h.issues.join(' · ') : 'No issues detected'}
            >
              <div className="relative w-7 h-7 shrink-0">
                <svg className="w-7 h-7 -rotate-90" viewBox="0 0 28 28">
                  <circle cx="14" cy="14" r="10" fill="none" strokeWidth="2.5" stroke="currentColor"
                    className={h.status === 'healthy' ? 'text-emerald-200'
                      : h.status === 'warning' ? 'text-amber-200'
                      : 'text-red-200'} />
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
                    ${h.status === 'healthy' ? 'text-emerald-700'
                      : h.status === 'warning' ? 'text-amber-700'
                      : 'text-red-700'}`}>{h.score}</span>
                </div>
              </div>
              <div className="flex flex-col leading-none">
                <span className={`text-[10px] font-bold
                  ${h.status === 'healthy' ? 'text-emerald-700'
                    : h.status === 'warning' ? 'text-amber-700'
                    : 'text-red-700'}`}>
                  {h.status === 'healthy' ? 'Healthy' : h.status === 'warning' ? 'Warning' : 'Critical'}
                </span>
                <span className="text-[8px] text-gray-400 mt-0.5">Health Score</span>
              </div>
            </div>
          )}

          {/* ── Issue chips ── */}
          {h && h.issues.length > 0 && (
            <div className="hidden md:flex items-center gap-1.5">
              {h.issues.slice(0, 2).map((issue, i) => (
                <span key={i} className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[9px] font-semibold
                  ${h.status === 'critical'
                    ? 'bg-red-50 border border-red-200 text-red-600'
                    : 'bg-amber-50 border border-amber-200 text-amber-600'}`}>
                  <span className="w-1.5 h-1.5 rounded-full bg-current inline-block" />
                  {issue}
                </span>
              ))}
              {h.issues.length > 2 && (
                <span className="text-[9px] text-gray-400 font-medium">+{h.issues.length - 2}</span>
              )}
            </div>
          )}

          {/* ── Meta strip ── */}
          <div className="hidden lg:flex items-center gap-1.5 text-[10px] text-gray-400 font-medium">
            {rd?.instanceClass && (
              <span className="flex items-center gap-1">
                <Icon icon="mdi:chip" width={10} color="#d1d5db" className="inline" />{rd.instanceClass}
              </span>
            )}
            {rd?.region && (
              <>
                <span className="text-gray-200">·</span>
                <span className="flex items-center gap-1">
                  <Icon icon="mdi:map-marker-outline" width={10} color="#d1d5db" className="inline" />{rd.region}
                </span>
              </>
            )}
            {rd?.estimatedMonthlyCost !== undefined && (
              <>
                <span className="text-gray-200">·</span>
                <span className="font-bold text-emerald-600 tabular-nums">
                  {fmt$(rd.estimatedMonthlyCost)}/mo
                </span>
              </>
            )}
          </div>

          <span className="w-px h-5 bg-[#e6dfd8] mx-0.5" />

          {/* ── Copy endpoint ── */}
          {endpoint && (
            <button
              onClick={() => {
                navigator.clipboard.writeText(endpoint);
                setCopiedEndpoint(true);
                setTimeout(() => setCopiedEndpoint(false), 2000);
              }}
              className={`group flex items-center gap-1.5 h-8 px-3 rounded-lg text-[11px] font-medium
                transition-all duration-200
                ${copiedEndpoint
                  ? 'bg-emerald-50 border border-emerald-300 text-emerald-600'
                  : 'bg-white border border-[#e6dfd8] text-[#6c6a64] hover:bg-[#faf9f5] hover:border-[#cc785c]/60 hover:text-[#cc785c]'}`}
              title={`Copy: ${endpoint}`}
            >
              {copiedEndpoint ? <Icon icon="mdi:check" width={13} /> : <Icon icon="mdi:content-copy" width={13} />}
              <span>{copiedEndpoint ? 'Copied!' : 'Copy'}</span>
            </button>
          )}

          {/* ── Refresh ── */}
          <button
            onClick={() => { refresh(); setLastRefreshed(new Date()); }}
            disabled={loading}
            className="group flex items-center justify-center w-8 h-8 rounded-lg
              bg-white border border-[#e6dfd8]
              text-[#6c6a64]
              hover:text-[#cc785c]
              hover:bg-[#faf9f5]
              hover:border-[#cc785c]/60
              transition-all duration-200 disabled:opacity-40"
            title={`Last updated: ${lastRefreshed.toLocaleTimeString()}`}
          >
            <Icon icon="mdi:refresh" width={14} className={loading ? 'animate-spin' : ''} />
          </button>

        </div>
      );
    } else {
      setInstanceHealth(null);
      setActions(
        <div className="flex items-center gap-2">
          {/* Azure badge */}
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#efe9de] border border-[#e6dfd8] text-[#3d3d3a] text-xs font-semibold">
            <Icon icon="mdi:microsoft-azure" width={12} color="#cc785c" />
            Azure
          </span>
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full bg-[#efe9de] border border-[#e6dfd8] text-[#3d3d3a] text-xs font-semibold">
            <Icon icon="mdi:flask-outline" width={12} />
            Beta
          </span>
          <button
            onClick={refresh}
            disabled={loading}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-[#cc785c] hover:bg-[#a9583e] text-white rounded-lg text-xs font-medium transition-colors disabled:opacity-50"
          >
            <Icon icon="mdi:refresh" width={14} className={loading ? 'animate-spin' : ''} />
            Refresh
          </button>
        </div>
      );
    }
    return () => clearActions();
  }, [setActions, clearActions, selectedInstance, subscriptionId, summary, loading, refresh, idle, instanceHealth, copiedEndpoint, lastRefreshed]);

  return (
    <main className={`flex-1 flex flex-col min-h-screen ${selectedInstance ? 'bg-white' : 'bg-gray-50'}`}>
      <div className={`flex-1 ${selectedInstance ? '' : 'p-6'}`}>

        {/* No account selected */}
        {!subscriptionId && (
          <div className="text-center py-24 bg-white border border-gray-200 rounded-xl shadow-sm">
            <div className="w-20 h-20 rounded-xl bg-sky-50 border border-sky-100 flex items-center justify-center mx-auto mb-6">
              <Icon icon="mdi:database-outline" width={40} color="#0284c7" />
            </div>
            <h3 className="text-xl font-bold text-gray-800 mb-2">Select an Azure Subscription</h3>
            <p className="text-sm text-gray-500 max-w-sm mx-auto leading-relaxed">
              Choose an Azure subscription from the sidebar to view Azure SQL / PostgreSQL / MySQL database optimisation data.
            </p>
          </div>
        )}

        {/* Error */}
        {subscriptionId && error && (
          <div className="mb-4 flex items-center gap-3 p-4 bg-red-50 border border-red-200 rounded-xl text-red-700 text-sm shadow-sm">
            <div className="w-7 h-7 rounded-lg bg-red-100 flex items-center justify-center flex-shrink-0">
              <Icon icon="mdi:alert-circle-outline" width={16} color="#ef4444" />
            </div>
            <span className="flex-1">{error}</span>
            <button onClick={refresh} className="px-3 py-1 rounded-lg bg-red-100 text-red-700 text-xs font-semibold hover:bg-red-200 transition-colors">Retry</button>
          </div>
        )}

        <AnimatePresence mode="wait">
          {subscriptionId && loading && (
            <motion.div key="loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }}>
              <LoadingSkeleton />
            </motion.div>
          )}

          {subscriptionId && !loading && selectedInstance && (
            <motion.div key="detail" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
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
                cloudProvider="azure"
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
            </motion.div>
          )}

          {subscriptionId && !loading && !selectedInstance && (
            <motion.div key="inventory" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}>
              {(() => {
                const agentOnlyHosts = new Set<string>();
                agentQueryStats.forEach(s => {
                  if (!s.dbHost) return;
                  const matchesAzure = inventory.some(i => i.endpoint && s.dbHost.startsWith(i.endpoint));
                  if (!matchesAzure) agentOnlyHosts.add(s.dbHost);
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
            </motion.div>
          )}
        </AnimatePresence>

      </div>
    </main>
  );
};

export default AzureDataOpsPage;
