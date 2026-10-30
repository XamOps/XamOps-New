import React, { useState, useCallback } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import {
  useDbOpsData,
  DbInstance, DbSavingsSummary, AgentQueryStat, AgentInstanceMetrics,
  DbHealthSnapshotDto,
} from '../hooks/useDbOpsData';
import DbInstanceDetailPage, { DrawerInstance } from '../components/DbOps/DbInstanceDetailDrawer';
import { DbAlertPanel } from '../components/DbOps/DbAlertPanel';
import {
  DBOPS_IDLE_UI_ENABLED, DBOPS_RIGHTSIZING_UI_ENABLED, DBOPS_HEALTH_UI_ENABLED,
} from '../constants/dbOpsFeatureFlags';

// ── Helpers ───────────────────────────────────────────────────────────────────

const fmt$ = (n: number) =>
  `$${n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

function EncBadge({ val }: { val: boolean }) {
  return val
    ? <span className="text-[#0b8f5a]"><Icon icon="mdi:lock-outline" width={13} className="inline mr-1" />Yes</span>
    : <span className="text-red-500"><Icon icon="mdi:lock-open-outline" width={13} className="inline mr-1" />No</span>;
}

/** Neutral pill + colored dot — the house pattern for source/category tags */
function SourcePill({ source }: { source: 'rds' | 'agent' }) {
  return (
    <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full border border-[rgba(0,0,0,0.08)] text-[11px] font-medium text-[rgba(0,0,0,0.6)] whitespace-nowrap">
      <span className={`w-1.5 h-1.5 rounded-full ${source === 'rds' ? 'bg-orange-500' : 'bg-violet-500'}`} />
      {source === 'rds' ? 'RDS' : 'Agent'}
    </span>
  );
}

// ── Summary Cards ─────────────────────────────────────────────────────────────

function SummaryCards({ s }: { s: DbSavingsSummary }) {
  const cards: { label: string; value: string; sub?: string; accent?: boolean }[] = [
    { label: 'Monthly DB Spend', value: fmt$(s.totalDbMonthlyCost) },
    { label: 'Savings Potential', value: fmt$(s.totalPotentialSavings), accent: true },
    { label: 'Total Instances', value: String(s.totalInstances) },
    ...(DBOPS_IDLE_UI_ENABLED ? [{ label: 'Idle Instances', value: `${s.idleInstances}`, sub: fmt$(s.idleSavingsPotential) + ' savings' }] : []),
    ...(DBOPS_RIGHTSIZING_UI_ENABLED ? [{ label: 'Right-sizing Candidates', value: `${s.rightsizingCandidates}`, sub: fmt$(s.rightsizingSavingsPotential) + ' savings' }] : []),
    { label: 'Snapshot Waste', value: fmt$(s.snapshotWasteCost) },
  ];
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4 mb-6">
      {cards.map(c => (
        <div key={c.label} className="bg-white border border-[rgba(0,0,0,0.08)] rounded-xl p-5">
          <p className="text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.45)] mb-2">{c.label}</p>
          <p className={`text-[22px] leading-7 font-bold tracking-[-0.01em] tabular-nums ${c.accent ? 'text-[#0b8f5a]' : 'text-[rgba(0,0,0,0.88)]'}`}>
            {c.value}
          </p>
          {c.sub && <p className="text-[11px] text-[rgba(0,0,0,0.4)] mt-1.5 tabular-nums">{c.sub}</p>}
        </div>
      ))}
    </div>
  );
}

// ── Inventory Tab ─────────────────────────────────────────────────────────────

function InventoryTab({
  inventory, agentStats, agentInstanceMetrics, onSelect, onRemove, healthSnapshots, healthFilter,
}: {
  inventory: DbInstance[];
  agentStats: AgentQueryStat[];
  agentInstanceMetrics: AgentInstanceMetrics[];
  onSelect: (inst: DrawerInstance) => void;
  onRemove: () => void;
  healthSnapshots: DbHealthSnapshotDto[];
  healthFilter: string | null;
}) {
  const [search, setSearch] = useState('');
  const [removing, setRemoving] = useState<string | null>(null);

  const agentHosts = new Map<string, AgentQueryStat[]>();
  agentStats.forEach(s => {
    if (!s.dbHost) return;
    const matchesRds = inventory.some(i => i.endpoint && s.dbHost.startsWith(i.endpoint));
    if (!matchesRds) {
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
  ).filter(r => !healthFilter || healthSnapshots.find(s => s.instanceId === r.instanceId)?.status === healthFilter);

  return (
    <div>
      <div className="flex items-center gap-3 mb-4 flex-wrap">
        <div className="relative flex-1 min-w-[180px] max-w-xs">
          <Icon icon="mdi:magnify" width={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.35)]" />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search instances..."
            className="w-full pl-9 pr-3 py-2 text-[12px] bg-white border border-[rgba(0,0,0,0.1)] rounded-md text-[rgba(0,0,0,0.88)] placeholder-[rgba(0,0,0,0.3)] focus:outline-none focus:border-[rgba(0,0,0,0.25)]"
          />
        </div>
        <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] text-[11px] font-medium">
          <Icon icon="mdi:database-outline" width={12} />
          {filtered.length} instances
        </span>
        <button
          onClick={() => onSelect({ instanceId: '', source: 'agent', engine: 'mysql' })}
          className="ml-auto flex items-center gap-1.5 px-3.5 py-2 bg-[#1BE68B] hover:bg-[#12c574] text-[#04231a] rounded-lg text-[12px] font-semibold transition-colors"
        >
          <Icon icon="mdi:plus" width={14} /> Connect a Database
        </button>
      </div>
      <div className="bg-white rounded-xl border border-[rgba(0,0,0,0.08)] overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-[rgba(0,0,0,0.06)]">
                {['Instance ID', 'Source', 'Engine', 'Class', 'Region', 'Status', 'Storage (GB)', 'Encrypted', 'Backups', 'Multi-AZ', 'Est. Cost/mo', ''].map(h => (
                  <th key={h} className="text-left px-4 py-2.5 text-[11px] font-semibold text-[rgba(0,0,0,0.45)] uppercase tracking-wider whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 ? (
                <tr>
                  <td colSpan={12}>
                    <div className="flex flex-col items-center justify-center py-16 gap-3">
                      <div className="w-12 h-12 rounded-full bg-[rgba(0,0,0,0.03)] flex items-center justify-center">
                        <Icon icon="mdi:database-outline" width={24} color="rgba(0,0,0,0.3)" />
                      </div>
                      <p className="text-sm font-medium text-[rgba(0,0,0,0.5)]">No instances found</p>
                    </div>
                  </td>
                </tr>
              ) : filtered.map(row => {
                const i = row.rdsData;
                const am = row.source === 'agent'
                  ? agentInstanceMetrics.find(m => m.dbHost === row.agentHost)
                  : undefined;
                const dbSizeGb = am?.dbSizeBytes ? (am.dbSizeBytes / 1073741824).toFixed(2) : null;
                return (
                  <tr
                    key={row.instanceId}
                    onClick={() => onSelect(row)}
                    className="group border-b border-[rgba(0,0,0,0.04)] last:border-0 hover:bg-[rgba(0,0,0,0.02)] cursor-pointer transition-colors"
                  >
                    <td className="px-4 py-3 font-semibold text-[rgba(0,0,0,0.85)] whitespace-nowrap">
                      <span className="group-hover:underline underline-offset-2">{row.instanceId}</span>
                      {am?.dbName && <span className="ml-1.5 text-[10px] text-[rgba(0,0,0,0.35)] font-normal">({am.dbName})</span>}
                    </td>
                    <td className="px-4 py-3">
                      <SourcePill source={row.source} />
                    </td>
                    <td className="px-4 py-3 text-[rgba(0,0,0,0.7)] font-medium">{row.engine}</td>
                    <td className="px-4 py-3 text-[rgba(0,0,0,0.7)] font-mono text-xs">
                      {i?.instanceClass ?? (am?.ec2InstanceType
                        ? <span title={am.ec2InstanceId ?? undefined}>{am.ec2InstanceType}</span>
                        : <span className="text-[rgba(0,0,0,0.3)]">—</span>)}
                    </td>
                    <td className="px-4 py-3 text-[rgba(0,0,0,0.7)] whitespace-nowrap">
                      {(i?.region ?? am?.region)
                        ? <span className="inline-flex items-center gap-1"><Icon icon="mdi:map-marker-outline" width={12} className="inline text-[rgba(0,0,0,0.35)]" />{i?.region ?? am?.region}</span>
                        : <span className="text-[rgba(0,0,0,0.3)]">—</span>}
                    </td>
                    <td className="px-4 py-3">
                      {i ? (
                        <span className={`inline-flex items-center gap-1.5 text-[12px] font-medium whitespace-nowrap ${i.status === 'available' ? 'text-[#0b8f5a]' : 'text-amber-600'}`}>
                          <span className={`w-1.5 h-1.5 rounded-full ${i.status === 'available' ? 'bg-[#1BE68B]' : 'bg-amber-500'}`} />
                          {i.status}
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 text-[12px] font-medium text-violet-600">
                          <span className="w-1.5 h-1.5 rounded-full bg-violet-500" />
                          live
                        </span>
                      )}
                    </td>
                    <td className="px-4 py-3 text-[rgba(0,0,0,0.7)] text-center tabular-nums">{i?.allocatedStorage ?? (dbSizeGb ? dbSizeGb : <span className="text-[rgba(0,0,0,0.3)]">—</span>)}</td>
                    <td className="px-4 py-3">{i ? <EncBadge val={i.storageEncrypted} /> : <span className="text-[rgba(0,0,0,0.3)]">—</span>}</td>
                    <td className="px-4 py-3">
                      {i ? (i.backupEnabled
                        ? <span className="text-[#0b8f5a] font-medium"><Icon icon="mdi:check" width={12} className="inline mr-1" />{i.backupRetentionDays}d</span>
                        : <span className="text-red-500 font-medium"><Icon icon="mdi:close" width={12} className="inline mr-1" />Off</span>)
                        : <span className="text-[rgba(0,0,0,0.3)]">—</span>}
                    </td>
                    <td className="px-4 py-3 text-[rgba(0,0,0,0.7)]">{i?.multiAz != null ? (i.multiAz ? <span className="text-[#0b8f5a]"><Icon icon="mdi:check" width={12} className="inline mr-1" />Yes</span> : <span className="text-[rgba(0,0,0,0.3)]">No</span>) : <span className="text-[rgba(0,0,0,0.3)]">—</span>}</td>
                    <td className="px-4 py-3 font-semibold text-[rgba(0,0,0,0.85)] whitespace-nowrap tabular-nums">
                      {i ? fmt$(i.estimatedMonthlyCost)
                        : (am?.ec2HourlyPrice && am.ec2HourlyPrice > 0
                          ? <span title="EC2 on-demand monthly cost">{fmt$(am.ec2HourlyPrice * 730)}</span>
                          : <span className="text-[rgba(0,0,0,0.3)] font-normal">—</span>)}
                    </td>
                    <td className="px-3 py-3" onClick={e => e.stopPropagation()}>
                      {row.source === 'rds' && (
                        <button
                          title="Remove from list (re-syncs with AWS)"
                          disabled={removing === row.instanceId}
                          onClick={async () => {
                            setRemoving(row.instanceId);
                            onRemove();
                            setRemoving(null);
                          }}
                          className="opacity-0 group-hover:opacity-100 p-1.5 rounded-lg text-[rgba(0,0,0,0.35)] hover:text-red-500 hover:bg-red-50 transition-all disabled:opacity-40"
                        >
                          {removing === row.instanceId
                            ? <i className="fas fa-spinner fa-spin text-xs" />
                            : <i className="fas fa-trash-can text-xs" />}
                        </button>
                      )}
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
          <div key={i} className="animate-pulse bg-white rounded-xl p-5 h-[96px] border border-[rgba(0,0,0,0.06)]">
            <div className="h-2.5 bg-[rgba(0,0,0,0.06)] rounded-full w-2/3 mb-3" />
            <div className="h-5 bg-[rgba(0,0,0,0.06)] rounded-full w-1/2" />
          </div>
        ))}
      </div>
      <div className="animate-pulse bg-white rounded-xl border border-[rgba(0,0,0,0.06)]">
        <div className="p-4 border-b border-[rgba(0,0,0,0.04)] flex gap-3">
          <div className="h-9 w-56 bg-[rgba(0,0,0,0.06)] rounded-md" />
          <div className="h-9 w-28 bg-[rgba(0,0,0,0.06)] rounded-full ml-auto" />
        </div>
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className="flex gap-4 px-4 py-3.5 border-b border-[rgba(0,0,0,0.04)] last:border-0">
            <div className="h-4 bg-[rgba(0,0,0,0.06)] rounded-full w-48" />
            <div className="h-4 bg-[rgba(0,0,0,0.06)] rounded-full w-16" />
            <div className="h-4 bg-[rgba(0,0,0,0.06)] rounded-full w-20" />
            <div className="h-4 bg-[rgba(0,0,0,0.06)] rounded-full w-24 ml-auto" />
          </div>
        ))}
      </div>
    </div>
  );
}

// ── Health Summary Card ───────────────────────────────────────────────────────

function HealthSummaryCard({ snapshots, onFilter }: {
  snapshots: DbHealthSnapshotDto[];
  onFilter: (status: string | null) => void;
}) {
  const critical = snapshots.filter(s => s.status === 'critical').length;
  const warning = snapshots.filter(s => s.status === 'warning').length;
  const healthy = snapshots.filter(s => s.status === 'healthy').length;
  if (snapshots.length === 0) return null;
  const pill = 'flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-white border border-[rgba(0,0,0,0.1)] hover:border-[rgba(0,0,0,0.3)] text-[11px] font-medium text-[rgba(0,0,0,0.65)] transition-colors';
  return (
    <div className="flex items-center gap-2 mb-4 flex-wrap">
      {critical > 0 && (
        <button onClick={() => onFilter('critical')} className={pill}>
          <span className="w-2 h-2 rounded-full bg-red-500" />{critical} critical
        </button>
      )}
      {warning > 0 && (
        <button onClick={() => onFilter('warning')} className={pill}>
          <span className="w-2 h-2 rounded-full bg-amber-500" />{warning} warning
        </button>
      )}
      {healthy > 0 && (
        <button onClick={() => onFilter(null)} className={pill}>
          <span className="w-2 h-2 rounded-full bg-[#1BE68B]" />{healthy} healthy
        </button>
      )}
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

const DataOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const { setTitle, setActions, clearActions } = useTopbar();
  const [selectedInstance, setSelectedInstance] = useState<DrawerInstance | null>(null);
  const [copiedEndpoint, setCopiedEndpoint] = useState(false);
  const [lastRefreshed, setLastRefreshed] = useState<Date>(new Date());
  const [instanceHealth, setInstanceHealth] = useState<{ score: number; status: 'healthy' | 'warning' | 'critical'; issues: string[] } | null>(null);

  const handleHealthChange = useCallback((h: { score: number; status: 'healthy' | 'warning' | 'critical'; issues: string[] }) => {
    setInstanceHealth(h);
  }, []);

  const [healthFilter, setHealthFilter] = useState<string | null>(null);

  const accountId = selectedAccountIds[0] ?? null;
  const {
    summary, inventory, metrics, costs, idle, rightsizing, snapshots, security, slowQueries,
    agentQueryStats, agentInstanceMetrics,
    healthSnapshots, dbAlerts,
    loading, error, refresh, fetchInstanceTimeseries, fetchAgentTimeseries, fetchAgentTableSizes,
    fetchAgentVacuumStats, fetchAgentUserActivity, fetchAgentIndexUsage, fetchAgentTableActivity,
  } = useDbOpsData(accountId);

  // Title: instance ID when selected, else 'DbOps'
  React.useEffect(() => {
    setTitle(selectedInstance ? (selectedInstance.instanceId || 'Connect Database') : 'DbOps');
    return () => setTitle('');
  }, [setTitle, selectedInstance]);

  // Actions: instance metadata + back btn when selected, else normal actions
  React.useEffect(() => {
    if (selectedInstance) {
      const rd = selectedInstance.rdsData;
      const engine = rd?.engine ?? selectedInstance.engine ?? 'unknown';
      const instanceIdle = idle.find(i => i.instanceId === selectedInstance.instanceId);
      const endpoint = rd?.endpoint ?? selectedInstance.agentHost ?? '';
      const h = instanceHealth;

      setActions(
        <div className="flex items-center gap-2">

          {/* ── Back ── */}
          <button
            onClick={() => { setSelectedInstance(null); setInstanceHealth(null); }}
            className="flex items-center gap-1.5 h-8 px-3 rounded-md text-[11px] font-medium
              bg-white border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)]
              hover:text-[rgba(0,0,0,0.88)] hover:border-[rgba(0,0,0,0.25)] transition-colors"
          >
            <Icon icon="mdi:chevron-left" width={14} />
            Back
          </button>

          {/* ── Instance identity card ── */}
          <div className="flex items-center gap-2 h-8 px-3 rounded-md bg-white border border-[rgba(0,0,0,0.1)]">
            <Icon
              icon={selectedInstance.source === 'rds' ? 'mdi:database-outline' : 'mdi:server-outline'}
              width={13}
              color="rgba(0,0,0,0.45)"
            />
            <span className="text-[11px] text-[rgba(0,0,0,0.6)] font-medium">{engine}</span>
            <SourcePill source={selectedInstance.source} />
            {rd?.status && (
              <span className="flex items-center gap-1 text-[11px] font-medium text-[#0b8f5a]">
                <span className="w-1.5 h-1.5 rounded-full bg-[#1BE68B]" />
                {rd.status}
              </span>
            )}
            {DBOPS_IDLE_UI_ENABLED && instanceIdle && (
              <span className="inline-flex items-center gap-1 text-[11px] font-medium text-amber-600">
                <Icon icon="mdi:moon-waning-crescent" width={11} />Idle
              </span>
            )}
          </div>

          {/* ── Health score ── disabled: onHealthChange is gated by DBOPS_HEALTH_UI_ENABLED, so instanceHealth stays null and this never renders */}
          {h && (
            <div
              className={`flex items-center gap-2 h-8 px-2.5 rounded-md border
                ${h.status === 'healthy' ? 'bg-emerald-50 border-emerald-100'
                  : h.status === 'warning' ? 'bg-amber-50 border-amber-100'
                    : 'bg-red-50 border-red-100'}`}
              title={h.issues.length > 0 ? h.issues.join(' · ') : 'No issues detected'}
            >
              <div className="relative w-6 h-6 shrink-0">
                <svg className="w-6 h-6 -rotate-90" viewBox="0 0 28 28">
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
                  <span className={`text-[9px] font-bold leading-none tabular-nums
                    ${h.status === 'healthy' ? 'text-emerald-700'
                      : h.status === 'warning' ? 'text-amber-700'
                        : 'text-red-700'}`}>{h.score}</span>
                </div>
              </div>
              <span className={`text-[11px] font-semibold
                ${h.status === 'healthy' ? 'text-emerald-700'
                  : h.status === 'warning' ? 'text-amber-700'
                    : 'text-red-700'}`}>
                {h.status === 'healthy' ? 'Healthy' : h.status === 'warning' ? 'Warning' : 'Critical'}
              </span>
            </div>
          )}

          {/* ── Issue chips ── */}
          {h && h.issues.length > 0 && (
            <div className="hidden md:flex items-center gap-1.5">
              {h.issues.slice(0, 2).map((issue, i) => (
                <span key={i} className={`inline-flex items-center gap-1.5 px-2 py-1 rounded-full border text-[10px] font-medium
                  ${h.status === 'critical'
                    ? 'bg-white border-red-200 text-red-600'
                    : 'bg-white border-amber-200 text-amber-600'}`}>
                  <span className="w-1.5 h-1.5 rounded-full bg-current inline-block" />
                  {issue}
                </span>
              ))}
              {h.issues.length > 2 && (
                <span className="text-[10px] text-[rgba(0,0,0,0.35)] font-medium">
                  +{h.issues.length - 2}
                </span>
              )}
            </div>
          )}

          {/* ── Meta strip (class · region · cost) ── */}
          <div className="hidden lg:flex items-center gap-1.5 text-[11px] text-[rgba(0,0,0,0.45)] font-medium">
            {rd?.instanceClass && (
              <span className="flex items-center gap-1">
                <Icon icon="mdi:chip" width={11} className="inline text-[rgba(0,0,0,0.3)]" />{rd.instanceClass}
              </span>
            )}
            {rd?.region && (
              <>
                <span className="text-[rgba(0,0,0,0.2)]">·</span>
                <span className="flex items-center gap-1">
                  <Icon icon="mdi:map-marker-outline" width={11} className="inline text-[rgba(0,0,0,0.3)]" />{rd.region}
                </span>
              </>
            )}
            {rd?.estimatedMonthlyCost !== undefined && (
              <>
                <span className="text-[rgba(0,0,0,0.2)]">·</span>
                <span className="font-semibold text-[rgba(0,0,0,0.75)] tabular-nums">
                  {fmt$(rd.estimatedMonthlyCost)}/mo
                </span>
              </>
            )}
          </div>

          {/* ── Vertical separator ── */}
          <span className="w-px h-5 bg-[rgba(0,0,0,0.1)] mx-0.5" />

          {/* ── Copy endpoint ── */}
          {endpoint && (
            <button
              onClick={() => {
                navigator.clipboard.writeText(endpoint);
                setCopiedEndpoint(true);
                setTimeout(() => setCopiedEndpoint(false), 2000);
              }}
              className={`flex items-center gap-1.5 h-8 px-3 rounded-md text-[11px] font-medium transition-colors
                ${copiedEndpoint
                  ? 'bg-emerald-50 border border-emerald-200 text-[#0b8f5a]'
                  : 'bg-white border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.6)] hover:border-[rgba(0,0,0,0.25)] hover:text-[rgba(0,0,0,0.88)]'}`}
              title={`Copy: ${endpoint}`}
            >
              {copiedEndpoint ? <Icon icon="mdi:check" width={13} /> : <Icon icon="mdi:content-copy" width={13} />}
              <span>{copiedEndpoint ? 'Copied!' : 'Copy'}</span>
            </button>
          )}

          {/* ── Last-updated + Refresh ── */}
          <button
            onClick={() => { refresh(); setLastRefreshed(new Date()); }}
            disabled={loading}
            className="flex items-center justify-center w-8 h-8 rounded-md
              bg-white border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)]
              hover:text-[rgba(0,0,0,0.88)] hover:border-[rgba(0,0,0,0.25)]
              transition-colors disabled:opacity-40"
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
          <span className="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.55)] text-[11px] font-medium">
            <Icon icon="mdi:flask-outline" width={12} />
            Beta
          </span>
          {DBOPS_HEALTH_UI_ENABLED && <DbAlertPanel alerts={dbAlerts ?? []} />}
          <button
            onClick={refresh}
            disabled={loading}
            className="flex items-center gap-1.5 px-3 py-1.5 bg-white border border-[rgba(0,0,0,0.1)] text-[rgba(0,0,0,0.65)] hover:text-[rgba(0,0,0,0.88)] hover:border-[rgba(0,0,0,0.25)] rounded-md text-xs font-medium transition-colors disabled:opacity-50"
          >
            <Icon icon="mdi:refresh" width={14} className={loading ? 'animate-spin' : ''} />
            Refresh
          </button>
        </div>
      );
    }
    return () => clearActions();
  }, [setActions, clearActions, selectedInstance, accountId, summary, loading, refresh, idle, instanceHealth, copiedEndpoint, lastRefreshed]);

  return (
    <main className="flex-1 flex flex-col min-h-screen">
      <div className={`flex-1 ${selectedInstance ? '' : 'p-6'}`}>

        {/* No account selected */}
        {!accountId && (
          <div className="text-center py-24 bg-white border border-[rgba(0,0,0,0.08)] rounded-xl">
            <div className="w-14 h-14 rounded-full bg-[rgba(0,0,0,0.03)] flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:database-outline" width={26} color="rgba(0,0,0,0.35)" />
            </div>
            <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">Select an AWS Account</h3>
            <p className="text-sm text-[rgba(0,0,0,0.5)] max-w-sm mx-auto leading-relaxed">
              Choose an AWS account from the sidebar to view database cost optimisation data.
            </p>
          </div>
        )}

        {/* Error */}
        {accountId && error && (
          <div className="mb-4 flex items-center gap-3 p-4 bg-red-50 border border-red-100 rounded-xl text-red-700 text-sm">
            <Icon icon="mdi:alert-circle-outline" width={18} color="#ef4444" className="flex-shrink-0" />
            <span className="flex-1">{error}</span>
            <button onClick={refresh} className="px-3 py-1 rounded-md bg-white border border-red-200 text-red-700 text-xs font-semibold hover:bg-red-100 transition-colors">Retry</button>
          </div>
        )}

        <AnimatePresence mode="wait">
          {accountId && loading && (
            <motion.div key="loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }}>
              <LoadingSkeleton />
            </motion.div>
          )}

          {accountId && !loading && selectedInstance && (
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

          {accountId && !loading && !selectedInstance && (
            <motion.div key="inventory" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}>
              {(() => {
                // Augment backend summary with agent-only instances not in RDS inventory
                const agentOnlyHosts = new Set<string>();
                agentQueryStats.forEach(s => {
                  if (!s.dbHost) return;
                  const matchesRds = inventory.some(i => i.endpoint && s.dbHost.startsWith(i.endpoint));
                  if (!matchesRds) agentOnlyHosts.add(s.dbHost);
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
              {DBOPS_HEALTH_UI_ENABLED && <HealthSummaryCard snapshots={healthSnapshots} onFilter={setHealthFilter} />}
              <InventoryTab inventory={inventory} agentStats={agentQueryStats} agentInstanceMetrics={agentInstanceMetrics} onSelect={setSelectedInstance} onRemove={refresh} healthSnapshots={healthSnapshots} healthFilter={healthFilter} />
            </motion.div>
          )}
        </AnimatePresence>

      </div>
    </main>
  );
};

export default DataOpsPage;
