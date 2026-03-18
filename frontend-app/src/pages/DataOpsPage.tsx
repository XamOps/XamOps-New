import React, { useState } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import {
  useDbOpsData,
  DbInstance, DbSavingsSummary, AgentQueryStat, AgentInstanceMetrics,
} from '../hooks/useDbOpsData';
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
    { icon: 'fas fa-dollar-sign',  label: 'Monthly DB Spend',         value: fmt$(s.totalDbMonthlyCost),      accent: 'border-sky-500',    iconBg: 'from-sky-500 to-blue-600' },
    { icon: 'fas fa-piggy-bank',   label: 'Savings Potential',         value: fmt$(s.totalPotentialSavings),   accent: 'border-emerald-500', iconBg: 'from-emerald-500 to-teal-600' },
    { icon: 'fas fa-server',       label: 'Total Instances',           value: String(s.totalInstances),        accent: 'border-indigo-500',  iconBg: 'from-indigo-500 to-purple-600' },
    { icon: 'fas fa-moon',         label: 'Idle Instances',            value: `${s.idleInstances} (${fmt$(s.idleSavingsPotential)})`, accent: 'border-amber-500', iconBg: 'from-amber-500 to-orange-600' },
    { icon: 'fas fa-compress-alt', label: 'Right-sizing Candidates',   value: `${s.rightsizingCandidates} (${fmt$(s.rightsizingSavingsPotential)})`, accent: 'border-pink-500', iconBg: 'from-pink-500 to-rose-600' },
    { icon: 'fas fa-camera',       label: 'Snapshot Waste',            value: fmt$(s.snapshotWasteCost),       accent: 'border-violet-500',  iconBg: 'from-violet-500 to-purple-600' },
  ];
  return (
    <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4 mb-6">
      {cards.map(c => (
        <div
          key={c.label}
          className={`bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 border-l-4 ${c.accent} p-4 flex flex-col gap-2 hover:-translate-y-0.5 hover:shadow-md transition-all duration-200`}
        >
          <div className={`w-8 h-8 rounded-lg bg-gradient-to-br ${c.iconBg} flex items-center justify-center`}>
            <i className={`${c.icon} text-white text-xs`} />
          </div>
          <p className="text-[11px] text-gray-700 dark:text-gray-400 leading-tight font-medium">{c.label}</p>
          <p className="text-sm font-bold text-gray-800 dark:text-gray-100 leading-tight">{c.value}</p>
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
  );

  return (
    <div>
      <div className="flex items-center gap-3 mb-4">
        <div className="relative flex-1 max-w-xs">
          <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-gray-400 text-sm" />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search instances..."
            className="w-full pl-9 pr-3 py-2 text-sm border border-gray-200 dark:border-gray-600 rounded-lg bg-white dark:bg-gray-800 text-gray-800 dark:text-gray-200 focus:outline-none focus:ring-2 focus:ring-sky-500"
          />
        </div>
        <span className="text-sm text-gray-700 dark:text-gray-400">{filtered.length} instances</span>
        <button
          onClick={() => onSelect({ instanceId: '', source: 'agent', engine: 'mysql' })}
          className="ml-auto flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 text-white rounded-lg text-sm font-medium transition-colors shadow-sm"
        >
          <i className="fas fa-plus text-xs" /> Connect a Database
        </button>
      </div>
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
              {['Instance ID', 'Source', 'Engine', 'Class', 'Region', 'Status', 'Storage (GB)', 'Encrypted', 'Backups', 'Multi-AZ', 'Est. Cost/mo'].map(h => (
                <th key={h} className="text-left px-4 py-3 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider whitespace-nowrap">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 ? (
              <tr><td colSpan={11} className="text-center py-12 text-gray-600 dark:text-gray-500">No instances found</td></tr>
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
                  className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-indigo-50/40 dark:hover:bg-indigo-900/10 transition-colors cursor-pointer"
                >
                  <td className="px-4 py-3 font-medium text-indigo-700 dark:text-indigo-400 whitespace-nowrap hover:underline">
                    {row.instanceId}
                    {am?.dbName && <span className="ml-1.5 text-[10px] text-gray-400 font-normal">({am.dbName})</span>}
                  </td>
                  <td className="px-4 py-3">
                    {row.source === 'rds'
                      ? <span className="px-2 py-0.5 text-xs rounded-full bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400 font-medium">RDS</span>
                      : <span className="px-2 py-0.5 text-xs rounded-full bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400 font-medium">Agent</span>}
                  </td>
                  <td className="px-4 py-3 text-gray-800 dark:text-gray-300">{row.engine}</td>
                  <td className="px-4 py-3 text-gray-800 dark:text-gray-300 font-mono text-xs">
                    {i?.instanceClass ?? (am?.ec2InstanceType
                      ? <span title={am.ec2InstanceId ?? undefined}>{am.ec2InstanceType}</span>
                      : '—')}
                  </td>
                  <td className="px-4 py-3 text-gray-800 dark:text-gray-300 whitespace-nowrap">{i?.region ?? am?.region ?? '—'}</td>
                  <td className="px-4 py-3">
                    {i ? (
                      <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${i.status === 'available' ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400' : 'bg-yellow-100 text-yellow-700'}`}>
                        {i.status}
                      </span>
                    ) : <span className="px-2 py-0.5 rounded-full text-xs bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400">agent</span>}
                  </td>
                  <td className="px-4 py-3 text-gray-800 dark:text-gray-300 text-center">{i?.allocatedStorage ?? (dbSizeGb ? dbSizeGb : '—')}</td>
                  <td className="px-4 py-3">{i ? <EncBadge val={i.storageEncrypted} /> : <span className="text-gray-400">—</span>}</td>
                  <td className="px-4 py-3">
                    {i ? (i.backupEnabled
                      ? <span className="text-green-600 dark:text-green-400"><i className="fas fa-check mr-1" />{i.backupRetentionDays}d</span>
                      : <span className="text-red-500"><i className="fas fa-times mr-1" />Off</span>)
                    : <span className="text-gray-400">—</span>}
                  </td>
                  <td className="px-4 py-3 text-gray-800 dark:text-gray-300">{i?.multiAz ?? '—'}</td>
                  <td className="px-4 py-3 font-semibold text-gray-800 dark:text-gray-200 whitespace-nowrap">
                    {i ? fmt$(i.estimatedMonthlyCost)
                      : (am?.ec2HourlyPrice && am.ec2HourlyPrice > 0
                          ? <span title="EC2 on-demand monthly cost">{fmt$(am.ec2HourlyPrice * 730)}</span>
                          : '—')}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ── Loading Skeleton ──────────────────────────────────────────────────────────

function LoadingSkeleton() {
  return (
    <div className="space-y-4 animate-pulse">
      <div className="grid grid-cols-2 md:grid-cols-3 xl:grid-cols-6 gap-4">
        {Array.from({ length: 6 }).map((_, i) => (
          <div key={i} className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl p-4 h-24 border border-gray-100 dark:border-gray-700/50 border-l-4 border-l-gray-200 dark:border-l-gray-600">
            <div className="w-9 h-9 bg-gray-200 dark:bg-gray-600 rounded-lg mb-3" />
            <div className="h-3 bg-gray-200 dark:bg-gray-600 rounded w-2/3 mb-2" />
            <div className="h-4 bg-gray-200 dark:bg-gray-600 rounded w-1/2" />
          </div>
        ))}
      </div>
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl h-64 border border-gray-100 dark:border-gray-700/50" />
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

const DataOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const { setTitle, setActions, clearActions } = useTopbar();
  const [selectedInstance, setSelectedInstance] = useState<DrawerInstance | null>(null);

  const accountId = selectedAccountIds[0] ?? null;
  const {
    summary, inventory, metrics, idle, rightsizing, snapshots, security, slowQueries,
    agentQueryStats, agentInstanceMetrics,
    loading, error, refresh, fetchInstanceTimeseries, fetchAgentTimeseries, fetchAgentTableSizes,
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

      setActions(
        <div className="flex items-center gap-2 flex-wrap">
          <button
            onClick={() => setSelectedInstance(null)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium text-gray-600 dark:text-gray-400 hover:bg-gray-100 dark:hover:bg-gray-700 border border-gray-200 dark:border-gray-600 transition-colors"
          >
            <i className="fas fa-arrow-left text-[10px]" />
            Back
          </button>
          {selectedInstance.source === 'rds'
            ? <span className="px-2 py-0.5 text-xs rounded-full bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400 font-medium">RDS</span>
            : <span className="px-2 py-0.5 text-xs rounded-full bg-purple-100 text-purple-700 dark:bg-purple-900/30 dark:text-purple-400 font-medium">EC2 / Agent</span>}
          {rd?.status && (
            <span className={`px-2 py-0.5 text-xs rounded-full font-medium ${rd.status === 'available' ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400' : 'bg-yellow-100 text-yellow-700'}`}>
              {rd.status}
            </span>
          )}
          {instanceIdle && (
            <span className="px-2 py-0.5 text-xs rounded-full bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400 font-medium">
              <i className="fas fa-moon mr-1" />Idle
            </span>
          )}
          <span className="hidden sm:flex items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
            <span><i className="fas fa-database mr-1" />{engine}</span>
            {rd?.instanceClass && <span><i className="fas fa-server mr-1" />{rd.instanceClass}</span>}
            {rd?.region && <span><i className="fas fa-map-marker-alt mr-1" />{rd.region}</span>}
          </span>
          {rd?.estimatedMonthlyCost !== undefined && (
            <span className="text-xs font-semibold text-emerald-600 dark:text-emerald-400">
              {fmt$(rd.estimatedMonthlyCost)}/mo
            </span>
          )}
        </div>
      );
    } else {
      setActions(
        <div className="flex items-center gap-2">
          {accountId && !loading && (
            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800 text-emerald-700 dark:text-emerald-400 text-xs font-semibold">
              <span className="relative flex h-2 w-2">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
                <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500" />
              </span>
              Live
            </div>
          )}
          {summary && (
            <div className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-sky-50 dark:bg-sky-900/20 border border-sky-200 dark:border-sky-800 text-sky-700 dark:text-sky-400 text-xs font-semibold">
              <i className="fas fa-database text-[10px]" />
              {fmt$(summary.totalDbMonthlyCost)}/mo
            </div>
          )}
          {summary && summary.totalPotentialSavings > 0 && (
            <div className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800 text-emerald-700 dark:text-emerald-400 text-xs font-semibold">
              <i className="fas fa-piggy-bank text-[10px]" />
              {fmt$(summary.totalPotentialSavings)} potential
            </div>
          )}
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
  }, [setActions, clearActions, selectedInstance, accountId, summary, loading, refresh, idle]);

  return (
    <main className="dataops-page flex-1 flex flex-col min-h-screen bg-transparent">
      <div className="flex-1 p-6">

        {/* No account selected */}
        {!accountId && (
          <div className="text-center py-20 bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50">
            <div className="w-16 h-16 bg-gray-100 dark:bg-gray-700 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-database text-gray-400 text-2xl" />
            </div>
            <h3 className="text-lg font-semibold text-gray-700 dark:text-gray-300 mb-2">Select an AWS Account</h3>
            <p className="text-sm text-gray-600 dark:text-gray-500">Choose an AWS account from the sidebar to view database cost optimisation data.</p>
          </div>
        )}

        {/* Error */}
        {accountId && error && (
          <div className="mb-4 flex items-center gap-3 p-4 bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-xl text-red-700 dark:text-red-400 text-sm">
            <i className="fas fa-exclamation-circle" />
            {error}
            <button onClick={refresh} className="ml-auto underline font-medium">Retry</button>
          </div>
        )}

        {/* Loading */}
        {accountId && loading && <LoadingSkeleton />}

        {/* Instance detail — full page */}
        {accountId && !loading && selectedInstance && (
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
            fetchTimeseries={fetchInstanceTimeseries}
            fetchAgentTimeseries={fetchAgentTimeseries}
            fetchAgentTableSizes={fetchAgentTableSizes}
            onConnected={async (inst) => { await refresh(); setSelectedInstance(inst); }}
          />
        )}

        {/* Inventory */}
        {accountId && !loading && !selectedInstance && (
          <>
            {summary && <SummaryCards s={summary} />}
            <InventoryTab inventory={inventory} agentStats={agentQueryStats} agentInstanceMetrics={agentInstanceMetrics} onSelect={setSelectedInstance} />
          </>
        )}

      </div>
    </main>
  );
};

export default DataOpsPage;
