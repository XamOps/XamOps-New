import React, { useState } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import {
  useDbOpsData,
  DbInstance, DbMetrics, DbIdleInstance, DbRightsizing, DbCost,
  DbSnapshot, DbSecurityCheck, DbSavingsSummary, SlowQueriesResponse, AgentQueryStat,
} from '../hooks/useDbOpsData';

// ── Helpers ───────────────────────────────────────────────────────────────────

const fmt$ = (n: number) =>
  `$${n.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;

function StatusBadge({ status }: { status: string }) {
  const map: Record<string, string> = {
    PASS: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
    FAIL: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    WARN: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
  };
  return (
    <span className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold ${map[status] ?? 'bg-gray-100 text-gray-600'}`}>
      {status === 'PASS' && <i className="fas fa-check mr-1" />}
      {status === 'FAIL' && <i className="fas fa-times mr-1" />}
      {status === 'WARN' && <i className="fas fa-exclamation mr-1" />}
      {status}
    </span>
  );
}

function SeverityBadge({ severity }: { severity: string }) {
  const map: Record<string, string> = {
    HIGH:   'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
    MEDIUM: 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400',
    INFO:   'bg-blue-100 text-blue-600 dark:bg-blue-900/30 dark:text-blue-400',
  };
  return (
    <span className={`inline-flex px-2 py-0.5 rounded text-xs font-medium ${map[severity] ?? 'bg-gray-100 text-gray-600'}`}>
      {severity}
    </span>
  );
}

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

// ── Tab Bar ───────────────────────────────────────────────────────────────────

type Tab = 'overview' | 'performance' | 'slowqueries' | 'agentstats' | 'inventory' | 'cost' | 'security' | 'snapshots';

interface TabDef { id: Tab; label: string; icon: string; badge?: number }

function TabBar({ active, onChange, tabs }: { active: Tab; onChange: (t: Tab) => void; tabs: TabDef[] }) {
  return (
    <div className="flex border-b border-gray-200 dark:border-gray-700 mb-6 gap-0 overflow-x-auto">
      {tabs.map(t => (
        <button
          key={t.id}
          onClick={() => onChange(t.id)}
          className={`flex items-center gap-2 px-4 py-3 text-sm font-medium whitespace-nowrap border-b-2 -mb-px transition-all duration-150 ${
            active === t.id
              ? 'border-sky-500 text-sky-600 dark:text-sky-400'
              : 'border-transparent text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-300 hover:border-gray-300 dark:hover:border-gray-600'
          }`}
        >
          <i className={`${t.icon} text-xs`} />
          <span>{t.label}</span>
          {t.badge !== undefined && t.badge > 0 && (
            <span className="inline-flex items-center justify-center w-4 h-4 text-[10px] font-bold rounded-full bg-red-500 text-white leading-none">
              {t.badge > 99 ? '99+' : t.badge}
            </span>
          )}
        </button>
      ))}
    </div>
  );
}

// ── Overview Tab ──────────────────────────────────────────────────────────────

function OverviewTab({ summary, idle, rightsizing }: {
  summary: DbSavingsSummary | null;
  idle: DbIdleInstance[];
  rightsizing: DbRightsizing[];
}) {
  if (!summary) {
    return (
      <div className="text-center py-16 text-gray-600 dark:text-gray-500">
        <i className="fas fa-database text-4xl mb-4 block" />
        <p>No data available. Select an AWS account to get started.</p>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* Savings meter */}
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 p-6">
        <div className="flex items-center justify-between mb-3">
          <h3 className="font-semibold text-gray-800 dark:text-gray-100 flex items-center gap-2">
            <i className="fas fa-piggy-bank text-emerald-500" /> Total Savings Opportunity
          </h3>
          <span className="text-2xl font-bold text-emerald-600 dark:text-emerald-400">
            {fmt$(summary.totalPotentialSavings)}
            <span className="text-sm font-normal text-gray-500 ml-1">/mo</span>
          </span>
        </div>
        <div className="w-full bg-gray-100 dark:bg-gray-700 rounded-full h-3 mb-2">
          <div
            className="bg-gradient-to-r from-emerald-400 to-teal-500 h-3 rounded-full transition-all duration-700"
            style={{ width: `${Math.min(summary.savingsPercentage, 100)}%` }}
          />
        </div>
        <p className="text-xs text-gray-700 dark:text-gray-400">
          {summary.savingsPercentage.toFixed(1)}% of {fmt$(summary.totalDbMonthlyCost)} monthly DB spend can be optimised
        </p>
      </div>

      {/* Breakdown cards */}
      <div className="grid md:grid-cols-3 gap-4">
        {[
          { label: 'Idle Instances',  count: summary.idleInstances,          savings: summary.idleSavingsPotential,        icon: 'fas fa-moon',         color: 'text-amber-500',  desc: 'No connections for 7 days' },
          { label: 'Right-sizing',    count: summary.rightsizingCandidates,   savings: summary.rightsizingSavingsPotential, icon: 'fas fa-compress-alt', color: 'text-pink-500',   desc: 'Over-provisioned instances' },
          { label: 'Snapshot Waste',  count: null,                            savings: summary.snapshotWasteCost,           icon: 'fas fa-camera',       color: 'text-violet-500', desc: 'Manual snapshots >30 days old' },
        ].map(item => (
          <div key={item.label} className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 p-5">
            <div className="flex items-center gap-3 mb-3">
              <i className={`${item.icon} ${item.color} text-xl`} />
              <div>
                <p className="font-semibold text-gray-800 dark:text-gray-100 text-sm">{item.label}</p>
                <p className="text-xs text-gray-700 dark:text-gray-400">{item.desc}</p>
              </div>
            </div>
            {item.count !== null && <p className="text-3xl font-bold text-gray-800 dark:text-gray-100 mb-1">{item.count}</p>}
            <p className="text-sm font-semibold text-emerald-600 dark:text-emerald-400">{fmt$(item.savings)}/mo potential</p>
          </div>
        ))}
      </div>

      {/* Top idle */}
      {idle.length > 0 && (
        <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 p-5">
          <h3 className="font-semibold text-gray-800 dark:text-gray-100 mb-4 flex items-center gap-2">
            <i className="fas fa-moon text-amber-500" /> Top Idle Instances
          </h3>
          <div className="space-y-3">
            {idle.slice(0, 5).map(i => (
              <div key={i.instanceId} className="flex items-center justify-between py-2 border-b border-gray-50 dark:border-gray-700/50 last:border-0">
                <div>
                  <p className="text-sm font-medium text-gray-800 dark:text-gray-200">{i.instanceId}</p>
                  <p className="text-xs text-gray-700 dark:text-gray-400">{i.engine} · {i.instanceClass} · {i.region}</p>
                </div>
                <div className="text-right">
                  <p className="text-sm font-semibold text-emerald-600 dark:text-emerald-400">{fmt$(i.potentialSavings)}/mo</p>
                  <p className="text-xs text-gray-400">{i.avgCpuPercent.toFixed(1)}% avg CPU</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Top right-sizing */}
      {rightsizing.length > 0 && (
        <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 p-5">
          <h3 className="font-semibold text-gray-800 dark:text-gray-100 mb-4 flex items-center gap-2">
            <i className="fas fa-compress-alt text-pink-500" /> Right-sizing Opportunities
          </h3>
          <div className="space-y-3">
            {rightsizing.slice(0, 5).map(r => (
              <div key={r.instanceId} className="flex items-center justify-between py-2 border-b border-gray-50 dark:border-gray-700/50 last:border-0">
                <div>
                  <p className="text-sm font-medium text-gray-800 dark:text-gray-200">{r.instanceId}</p>
                  <p className="text-xs text-gray-700 dark:text-gray-400">
                    {r.currentInstanceClass} <i className="fas fa-arrow-right mx-1 text-gray-300" /> {r.recommendedInstanceClass} · {r.region}
                  </p>
                </div>
                <div className="text-right">
                  <p className="text-sm font-semibold text-emerald-600 dark:text-emerald-400">{fmt$(r.estimatedSavings)}/mo</p>
                  <p className="text-xs text-gray-400">{r.avgCpuPercent.toFixed(1)}% avg CPU</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

// ── Inventory Tab ─────────────────────────────────────────────────────────────

function InventoryTab({ inventory }: { inventory: DbInstance[] }) {
  const [search, setSearch] = useState('');
  const filtered = inventory.filter(i =>
    i.instanceId.toLowerCase().includes(search.toLowerCase()) ||
    i.engine.toLowerCase().includes(search.toLowerCase()) ||
    i.region.toLowerCase().includes(search.toLowerCase())
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
      </div>
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
              {['Instance ID', 'Engine', 'Class', 'Region', 'Status', 'Storage (GB)', 'Encrypted', 'Backups', 'Multi-AZ', 'Est. Cost/mo'].map(h => (
                <th key={h} className="text-left px-4 py-3 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider whitespace-nowrap">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 ? (
              <tr><td colSpan={10} className="text-center py-12 text-gray-600 dark:text-gray-500">No instances found</td></tr>
            ) : filtered.map(i => (
              <tr key={i.instanceId} className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-gray-50/50 dark:hover:bg-gray-700/30 transition-colors">
                <td className="px-4 py-3 font-medium text-gray-900 dark:text-gray-100 whitespace-nowrap">{i.instanceId}</td>
                <td className="px-4 py-3 text-gray-800 dark:text-gray-300">{i.engine}</td>
                <td className="px-4 py-3 text-gray-800 dark:text-gray-300 font-mono text-xs">{i.instanceClass}</td>
                <td className="px-4 py-3 text-gray-800 dark:text-gray-300 whitespace-nowrap">{i.region}</td>
                <td className="px-4 py-3">
                  <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${i.status === 'available' ? 'bg-green-100 text-green-700' : 'bg-yellow-100 text-yellow-700'}`}>
                    {i.status}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-800 dark:text-gray-300 text-center">{i.allocatedStorage}</td>
                <td className="px-4 py-3"><EncBadge val={i.storageEncrypted} /></td>
                <td className="px-4 py-3">
                  {i.backupEnabled
                    ? <span className="text-green-600 dark:text-green-400"><i className="fas fa-check mr-1" />{i.backupRetentionDays}d</span>
                    : <span className="text-red-500"><i className="fas fa-times mr-1" />Off</span>}
                </td>
                <td className="px-4 py-3 text-gray-800 dark:text-gray-300">{i.multiAz}</td>
                <td className="px-4 py-3 font-semibold text-gray-800 dark:text-gray-200 whitespace-nowrap">{fmt$(i.estimatedMonthlyCost)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ── Cost Tab ──────────────────────────────────────────────────────────────────

function CostTab({ costs, idle, rightsizing }: {
  costs: DbCost[];
  idle: DbIdleInstance[];
  rightsizing: DbRightsizing[];
}) {
  return (
    <div className="space-y-6">
      {/* Cost breakdown */}
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 p-5">
        <h3 className="font-semibold text-gray-800 dark:text-gray-100 mb-4 flex items-center gap-2">
          <i className="fas fa-dollar-sign text-sky-500" /> Month-to-Date DB Costs
        </h3>
        {costs.length === 0 ? (
          <p className="text-sm text-gray-600 dark:text-gray-500 py-4">No DB cost data available.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
                  {['Service', 'Region', 'Cost', '% of Total'].map(h => (
                    <th key={h} className="text-left px-3 py-2 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {costs.map((c, idx) => (
                  <tr key={idx} className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-gray-50/50 dark:hover:bg-gray-700/30">
                    <td className="px-3 py-2.5 font-medium text-gray-800 dark:text-gray-200">{c.service}</td>
                    <td className="px-3 py-2.5 text-gray-700 dark:text-gray-400">{c.region}</td>
                    <td className="px-3 py-2.5 font-semibold text-gray-800 dark:text-gray-100">{fmt$(c.cost)}</td>
                    <td className="px-3 py-2.5">
                      <div className="flex items-center gap-2">
                        <div className="flex-1 bg-gray-100 dark:bg-gray-700 rounded-full h-1.5 w-24">
                          <div className="bg-sky-500 h-1.5 rounded-full" style={{ width: `${c.percentOfTotal}%` }} />
                        </div>
                        <span className="text-xs text-gray-700 dark:text-gray-400 w-10 text-right">{c.percentOfTotal.toFixed(1)}%</span>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Idle instances */}
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 p-5">
        <h3 className="font-semibold text-gray-800 dark:text-gray-100 mb-1 flex items-center gap-2">
          <i className="fas fa-moon text-amber-500" /> Idle Instances
          <span className="ml-auto text-xs bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400 px-2 py-0.5 rounded-full">{idle.length} found</span>
        </h3>
        <p className="text-xs text-gray-700 dark:text-gray-400 mb-4">Avg CPU ≤ 5% and avg connections ≤ 1 over the last 7 days</p>
        {idle.length === 0 ? (
          <p className="text-sm text-gray-600 dark:text-gray-500 py-4"><i className="fas fa-check-circle text-green-500 mr-2" />No idle instances detected.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
                  {['Instance', 'Engine', 'Class', 'Region', 'Avg CPU', 'Avg Connections', 'Monthly Cost', 'Savings'].map(h => (
                    <th key={h} className="text-left px-3 py-2 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {idle.map(i => (
                  <tr key={i.instanceId} className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-amber-50/30 dark:hover:bg-amber-900/10">
                    <td className="px-3 py-2.5 font-medium text-gray-800 dark:text-gray-200">{i.instanceId}</td>
                    <td className="px-3 py-2.5 text-gray-700 dark:text-gray-400">{i.engine}</td>
                    <td className="px-3 py-2.5 font-mono text-xs text-gray-800 dark:text-gray-300">{i.instanceClass}</td>
                    <td className="px-3 py-2.5 text-gray-700 dark:text-gray-400">{i.region}</td>
                    <td className="px-3 py-2.5 text-amber-600 dark:text-amber-400 font-medium">{i.avgCpuPercent.toFixed(1)}%</td>
                    <td className="px-3 py-2.5 text-gray-800 dark:text-gray-300">{i.avgConnections.toFixed(1)}</td>
                    <td className="px-3 py-2.5 text-gray-700 dark:text-gray-300">{fmt$(i.estimatedMonthlyCost)}</td>
                    <td className="px-3 py-2.5 font-semibold text-emerald-600 dark:text-emerald-400">{fmt$(i.potentialSavings)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* Right-sizing */}
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 p-5">
        <h3 className="font-semibold text-gray-800 dark:text-gray-100 mb-1 flex items-center gap-2">
          <i className="fas fa-compress-alt text-pink-500" /> Right-sizing Recommendations
          <span className="ml-auto text-xs bg-pink-100 text-pink-700 dark:bg-pink-900/30 dark:text-pink-400 px-2 py-0.5 rounded-full">{rightsizing.length} found</span>
        </h3>
        <p className="text-xs text-gray-700 dark:text-gray-400 mb-4">Avg CPU ≤ 10% — instance class can be reduced one tier</p>
        {rightsizing.length === 0 ? (
          <p className="text-sm text-gray-600 dark:text-gray-500 py-4"><i className="fas fa-check-circle text-green-500 mr-2" />No right-sizing opportunities found.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
                  {['Instance', 'Engine', 'Region', 'Current', 'Recommended', 'Avg CPU', 'Current Cost', 'New Cost', 'Savings'].map(h => (
                    <th key={h} className="text-left px-3 py-2 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rightsizing.map(r => (
                  <tr key={r.instanceId} className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-pink-50/30 dark:hover:bg-pink-900/10">
                    <td className="px-3 py-2.5 font-medium text-gray-800 dark:text-gray-200">{r.instanceId}</td>
                    <td className="px-3 py-2.5 text-gray-700 dark:text-gray-400">{r.engine}</td>
                    <td className="px-3 py-2.5 text-gray-700 dark:text-gray-400">{r.region}</td>
                    <td className="px-3 py-2.5 font-mono text-xs text-gray-800 dark:text-gray-300">{r.currentInstanceClass}</td>
                    <td className="px-3 py-2.5 font-mono text-xs text-emerald-600 dark:text-emerald-400 font-semibold">{r.recommendedInstanceClass}</td>
                    <td className="px-3 py-2.5 text-pink-600 dark:text-pink-400 font-medium">{r.avgCpuPercent.toFixed(1)}%</td>
                    <td className="px-3 py-2.5 text-gray-800 dark:text-gray-300">{fmt$(r.currentMonthlyCost)}</td>
                    <td className="px-3 py-2.5 text-gray-800 dark:text-gray-300">{fmt$(r.recommendedMonthlyCost)}</td>
                    <td className="px-3 py-2.5 font-semibold text-emerald-600 dark:text-emerald-400">{fmt$(r.estimatedSavings)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}

// ── Security Tab ──────────────────────────────────────────────────────────────

function SecurityTab({ security }: { security: DbSecurityCheck[] }) {
  const fails = security.filter(c => c.status === 'FAIL').length;
  const warns = security.filter(c => c.status === 'WARN').length;

  return (
    <div>
      {security.length > 0 && (
        <div className="flex gap-3 mb-4 flex-wrap">
          {[
            { label: 'Failures', count: fails,                          color: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',     icon: 'fas fa-times-circle' },
            { label: 'Warnings', count: warns,                          color: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400', icon: 'fas fa-exclamation-triangle' },
            { label: 'Passed',   count: security.length - fails - warns, color: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',  icon: 'fas fa-check-circle' },
          ].map(s => (
            <div key={s.label} className={`flex items-center gap-2 px-4 py-2 rounded-lg ${s.color} text-sm font-semibold`}>
              <i className={s.icon} /> {s.count} {s.label}
            </div>
          ))}
        </div>
      )}
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
              {['Instance', 'Region', 'Check', 'Severity', 'Status', 'Details'].map(h => (
                <th key={h} className="text-left px-4 py-3 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {security.length === 0 ? (
              <tr><td colSpan={6} className="text-center py-12 text-gray-600 dark:text-gray-500">No security checks available</td></tr>
            ) : security.map((c, i) => (
              <tr
                key={i}
                className={`border-b border-gray-50 dark:border-gray-700/50 transition-colors ${
                  c.status === 'FAIL'
                    ? 'bg-red-50/40 dark:bg-red-900/10 hover:bg-red-50 dark:hover:bg-red-900/20'
                    : c.status === 'WARN'
                      ? 'bg-yellow-50/40 dark:bg-yellow-900/10 hover:bg-yellow-50 dark:hover:bg-yellow-900/20'
                      : 'hover:bg-gray-50/50 dark:hover:bg-gray-700/30'
                }`}
              >
                <td className="px-4 py-3 font-medium text-gray-800 dark:text-gray-200">{c.instanceId}</td>
                <td className="px-4 py-3 text-gray-700 dark:text-gray-400">{c.region}</td>
                <td className="px-4 py-3 text-gray-700 dark:text-gray-300">{c.checkName}</td>
                <td className="px-4 py-3"><SeverityBadge severity={c.severity} /></td>
                <td className="px-4 py-3"><StatusBadge status={c.status} /></td>
                <td className="px-4 py-3 text-gray-600 dark:text-gray-400 text-xs">{c.details}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ── Snapshots Tab ─────────────────────────────────────────────────────────────

function SnapshotsTab({ snapshots }: { snapshots: DbSnapshot[] }) {
  const totalCost = snapshots.reduce((s, n) => s + n.estimatedMonthlyCost, 0);

  return (
    <div>
      {snapshots.length > 0 && (
        <div className="flex items-center gap-3 mb-4 p-4 bg-violet-50 dark:bg-violet-900/20 border border-violet-100 dark:border-violet-800 rounded-xl">
          <i className="fas fa-camera text-violet-500 text-lg" />
          <div>
            <p className="text-sm font-semibold text-violet-800 dark:text-violet-300">
              {snapshots.length} old manual snapshots — {fmt$(totalCost)}/mo storage cost
            </p>
            <p className="text-xs text-violet-600 dark:text-violet-400">
              Manual snapshots older than 30 days. Review and delete if no longer needed.
            </p>
          </div>
        </div>
      )}
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl shadow-sm border border-gray-100 dark:border-gray-700/50 overflow-x-auto">
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
              {['Snapshot ID', 'DB Instance', 'Region', 'Age (days)', 'Size (GB)', 'Cost/mo', 'Recommendation'].map(h => (
                <th key={h} className="text-left px-4 py-3 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider whitespace-nowrap">{h}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {snapshots.length === 0 ? (
              <tr>
                <td colSpan={7} className="text-center py-12 text-gray-600 dark:text-gray-500">
                  <i className="fas fa-check-circle text-green-500 text-2xl mb-2 block" />
                  No old snapshots found.
                </td>
              </tr>
            ) : snapshots.map(s => (
              <tr key={s.snapshotId} className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-gray-50/50 dark:hover:bg-gray-700/30">
                <td className="px-4 py-3 font-medium text-gray-800 dark:text-gray-200 text-xs font-mono">{s.snapshotId}</td>
                <td className="px-4 py-3 text-gray-800 dark:text-gray-300">{s.instanceId}</td>
                <td className="px-4 py-3 text-gray-700 dark:text-gray-400">{s.region}</td>
                <td className="px-4 py-3">
                  <span className={`font-semibold ${s.ageDays > 90 ? 'text-red-600 dark:text-red-400' : 'text-amber-600 dark:text-amber-400'}`}>
                    {s.ageDays}
                  </span>
                </td>
                <td className="px-4 py-3 text-gray-800 dark:text-gray-300">{s.sizeGb}</td>
                <td className="px-4 py-3 font-semibold text-violet-600 dark:text-violet-400">{fmt$(s.estimatedMonthlyCost)}</td>
                <td className="px-4 py-3 text-xs text-gray-700 dark:text-gray-400">{s.recommendation}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

// ── Performance Tab (Datadog-style) ───────────────────────────────────────────

type HealthStatus = 'ok' | 'warn' | 'critical';

function instanceHealth(m: DbMetrics | undefined): HealthStatus {
  if (!m) return 'ok';
  if (m.avgCpuPercent > 80 || m.avgReadLatencyMs > 100 || m.avgWriteLatencyMs > 100 || m.replicaLagSeconds > 30 || m.avgDiskQueueDepth > 10) return 'critical';
  if (m.avgCpuPercent > 60 || m.avgReadLatencyMs > 30 || m.avgWriteLatencyMs > 30 || m.replicaLagSeconds > 10 || m.avgDiskQueueDepth > 5) return 'warn';
  return 'ok';
}

const statusDot: Record<HealthStatus, string> = {
  ok:       'bg-emerald-500',
  warn:     'bg-amber-400',
  critical: 'bg-red-500',
};

const latencyColor = (ms: number) =>
  ms === 0 ? 'text-gray-600 dark:text-gray-500' :
  ms > 100 ? 'text-red-600 dark:text-red-400 font-semibold' :
  ms > 30  ? 'text-amber-600 dark:text-amber-400' :
             'text-emerald-600 dark:text-emerald-400';

const cpuColor = (pct: number) =>
  pct > 80 ? 'text-red-600 dark:text-red-400' :
  pct > 60 ? 'text-amber-600 dark:text-amber-400' :
             'text-gray-700 dark:text-gray-300';

const MiniBar = ({ value, max, color }: { value: number; max: number; color: string }) => (
  <div className="flex items-center gap-1.5 w-28">
    <div className="flex-1 bg-gray-100 dark:bg-gray-700 rounded-full h-1.5">
      <div className={`${color} h-1.5 rounded-full transition-all`} style={{ width: `${Math.min((value / max) * 100, 100)}%` }} />
    </div>
    <span className="text-[11px] w-8 text-right tabular-nums text-gray-800 dark:text-gray-300">{value.toFixed(1)}</span>
  </div>
);

function PerformanceTab({ inventory, metrics }: { inventory: DbInstance[]; metrics: Record<string, DbMetrics> }) {
  const [sort, setSort] = useState<'cpu' | 'rlat' | 'wlat' | 'conn' | 'lag'>('cpu');

  const rows = [...inventory].sort((a, b) => {
    const ma = metrics[a.instanceId]; const mb = metrics[b.instanceId];
    if (!ma || !mb) return 0;
    const fields: Record<string, (m: DbMetrics) => number> = {
      cpu: m => m.avgCpuPercent, rlat: m => m.avgReadLatencyMs,
      wlat: m => m.avgWriteLatencyMs, conn: m => m.avgConnections, lag: m => m.replicaLagSeconds,
    };
    return (fields[sort](mb) ?? 0) - (fields[sort](ma) ?? 0);
  });

  const SortBtn = ({ id, label }: { id: typeof sort; label: string }) => (
    <button
      onClick={() => setSort(id)}
      className={`px-2 py-0.5 rounded text-[11px] font-medium transition-colors ${sort === id ? 'bg-sky-100 dark:bg-sky-900/30 text-sky-700 dark:text-sky-400' : 'text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-300'}`}
    >
      {label}
    </button>
  );

  return (
    <div className="space-y-5">
      {/* Fleet health overview */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          {
            label: 'Avg Read Latency', icon: 'fas fa-arrow-down', color: 'text-sky-500',
            value: inventory.length ? (Object.values(metrics).reduce((s, m) => s + m.avgReadLatencyMs, 0) / Math.max(Object.values(metrics).length, 1)).toFixed(2) + ' ms' : '—',
            sub: 'fleet average',
          },
          {
            label: 'Avg Write Latency', icon: 'fas fa-arrow-up', color: 'text-violet-500',
            value: inventory.length ? (Object.values(metrics).reduce((s, m) => s + m.avgWriteLatencyMs, 0) / Math.max(Object.values(metrics).length, 1)).toFixed(2) + ' ms' : '—',
            sub: 'fleet average',
          },
          {
            label: 'Replica Lag', icon: 'fas fa-sync-alt', color: 'text-amber-500',
            value: (() => { const lagging = Object.values(metrics).filter(m => m.replicaLagSeconds > 5).length; return lagging > 0 ? `${lagging} lagging` : 'All healthy'; })(),
            sub: '> 5s threshold',
          },
          {
            label: 'Storage Pressure', icon: 'fas fa-hdd', color: 'text-pink-500',
            value: (() => {
              const low = Object.values(metrics).filter(m => m.freeStorageSpaceGb > 0 && m.freeStorageSpaceGb < 10).length;
              return low > 0 ? `${low} low space` : 'All healthy';
            })(),
            sub: '< 10 GB free',
          },
        ].map(c => (
          <div key={c.label} className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50 p-4">
            <div className="flex items-center gap-2 mb-2">
              <i className={`${c.icon} ${c.color} text-xs`} />
              <span className="text-[11px] font-medium text-gray-700 dark:text-gray-400 uppercase tracking-wide">{c.label}</span>
            </div>
            <p className="text-lg font-bold text-gray-800 dark:text-gray-100">{c.value}</p>
            <p className="text-[11px] text-gray-700 dark:text-gray-400 mt-0.5">{c.sub}</p>
          </div>
        ))}
      </div>

      {/* Instance performance table */}
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50 overflow-hidden">
        <div className="flex items-center justify-between px-5 py-3 border-b border-gray-100 dark:border-gray-700/50">
          <h3 className="text-sm font-semibold text-gray-800 dark:text-gray-100 flex items-center gap-2">
            <i className="fas fa-chart-line text-sky-500 text-xs" /> Instance Health Monitor
          </h3>
          <div className="flex items-center gap-1">
            <span className="text-[11px] text-gray-700 dark:text-gray-400 mr-1">Sort by:</span>
            <SortBtn id="cpu"  label="CPU" />
            <SortBtn id="rlat" label="Read Lat" />
            <SortBtn id="wlat" label="Write Lat" />
            <SortBtn id="conn" label="Conns" />
            <SortBtn id="lag"  label="Replica Lag" />
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
                {['Health', 'Instance', 'Engine', 'CPU %', 'Connections', 'Read Lat (ms)', 'Write Lat (ms)', 'Disk Queue', 'Free Storage', 'Replica Lag', 'Swap (MB)', 'Burst %', 'Net RX', 'Net TX'].map(h => (
                  <th key={h} className="text-left px-3 py-2.5 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.length === 0 ? (
                <tr><td colSpan={14} className="text-center py-12 text-gray-600 dark:text-gray-500">No instances. Select an AWS account with RDS resources.</td></tr>
              ) : rows.map(inst => {
                const m = metrics[inst.instanceId];
                const health = instanceHealth(m);
                return (
                  <tr key={inst.instanceId} className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-gray-50/50 dark:hover:bg-gray-700/20 transition-colors">
                    {/* Health dot */}
                    <td className="px-3 py-3">
                      <div className="flex items-center gap-1.5">
                        <span className={`inline-flex w-2.5 h-2.5 rounded-full ${statusDot[health]} ${health !== 'ok' ? 'animate-pulse' : ''}`} />
                        <span className="text-[10px] font-medium text-gray-700 dark:text-gray-400 capitalize">{health}</span>
                      </div>
                    </td>
                    {/* Instance name */}
                    <td className="px-3 py-3">
                      <p className="font-medium text-gray-800 dark:text-gray-200 whitespace-nowrap text-xs">{inst.instanceId}</p>
                      <p className="text-[10px] text-gray-700 dark:text-gray-400">{inst.region} · {inst.instanceClass}</p>
                    </td>
                    <td className="px-3 py-3">
                      <span className="px-1.5 py-0.5 rounded text-[10px] font-medium bg-gray-100 dark:bg-gray-700 text-gray-800 dark:text-gray-300">{inst.engine}</span>
                    </td>
                    {/* CPU with mini bar */}
                    <td className="px-3 py-3">
                      {m ? <MiniBar value={m.avgCpuPercent} max={100} color={m.avgCpuPercent > 80 ? 'bg-red-500' : m.avgCpuPercent > 60 ? 'bg-amber-400' : 'bg-emerald-400'} /> : <span className="text-gray-600 dark:text-gray-500">—</span>}
                    </td>
                    {/* Connections with mini bar — uses per-instance max */}
                    <td className="px-3 py-3">
                      {m ? <MiniBar value={m.avgConnections} max={inst.maxConnections || 100} color={m.avgConnections / (inst.maxConnections || 100) > 0.8 ? 'bg-red-500' : 'bg-sky-400'} /> : <span className="text-gray-600 dark:text-gray-500">—</span>}
                    </td>
                    {/* Read latency */}
                    <td className={`px-3 py-3 tabular-nums ${m ? latencyColor(m.avgReadLatencyMs) : 'text-gray-600 dark:text-gray-500'}`}>
                      {m ? (m.avgReadLatencyMs > 0 ? m.avgReadLatencyMs.toFixed(2) : '—') : '—'}
                    </td>
                    {/* Write latency */}
                    <td className={`px-3 py-3 tabular-nums ${m ? latencyColor(m.avgWriteLatencyMs) : 'text-gray-600 dark:text-gray-500'}`}>
                      {m ? (m.avgWriteLatencyMs > 0 ? m.avgWriteLatencyMs.toFixed(2) : '—') : '—'}
                    </td>
                    {/* Disk queue */}
                    <td className={`px-3 py-3 tabular-nums ${m && m.avgDiskQueueDepth > 5 ? 'text-red-600 dark:text-red-400 font-semibold' : 'text-gray-800 dark:text-gray-300'}`}>
                      {m ? (m.avgDiskQueueDepth > 0 ? m.avgDiskQueueDepth.toFixed(2) : '—') : '—'}
                    </td>
                    {/* Free storage */}
                    <td className={`px-3 py-3 tabular-nums ${m && m.freeStorageSpaceGb > 0 && m.freeStorageSpaceGb < 10 ? 'text-red-600 dark:text-red-400 font-semibold' : 'text-gray-800 dark:text-gray-300'}`}>
                      {m ? (m.freeStorageSpaceGb > 0 ? m.freeStorageSpaceGb.toFixed(1) + ' GB' : '—') : '—'}
                    </td>
                    {/* Replica lag */}
                    <td className={`px-3 py-3 tabular-nums ${m && m.replicaLagSeconds > 10 ? 'text-red-600 dark:text-red-400 font-semibold' : m && m.replicaLagSeconds > 0 ? 'text-amber-600 dark:text-amber-400' : 'text-gray-600 dark:text-gray-500'}`}>
                      {m ? (m.replicaLagSeconds > 0 ? m.replicaLagSeconds.toFixed(1) + 's' : '—') : '—'}
                    </td>
                    {/* Swap */}
                    <td className={`px-3 py-3 tabular-nums ${m && m.avgSwapUsageMb > 100 ? 'text-amber-600 dark:text-amber-400' : 'text-gray-800 dark:text-gray-300'}`}>
                      {m ? (m.avgSwapUsageMb > 0 ? m.avgSwapUsageMb.toFixed(1) : '—') : '—'}
                    </td>
                    {/* Burst balance */}
                    <td className={`px-3 py-3 tabular-nums ${m && m.burstBalance > 0 && m.burstBalance < 20 ? 'text-red-600 dark:text-red-400 font-semibold' : 'text-gray-800 dark:text-gray-300'}`}>
                      {m ? (m.burstBalance > 0 ? m.burstBalance.toFixed(0) + '%' : '—') : '—'}
                    </td>
                    {/* Network RX */}
                    <td className="px-3 py-3 tabular-nums text-gray-800 dark:text-gray-300">
                      {m ? (m.avgNetworkReceiveMbps > 0 ? m.avgNetworkReceiveMbps.toFixed(2) : '—') : '—'}
                    </td>
                    {/* Network TX */}
                    <td className="px-3 py-3 tabular-nums text-gray-800 dark:text-gray-300">
                      {m ? (m.avgNetworkTransmitMbps > 0 ? m.avgNetworkTransmitMbps.toFixed(2) : '—') : '—'}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {/* Legend */}
        <div className="flex items-center gap-4 px-5 py-3 border-t border-gray-100 dark:border-gray-700/50 bg-gray-50/50 dark:bg-gray-700/20">
          {[
            { dot: 'bg-emerald-500', label: 'OK — All metrics healthy' },
            { dot: 'bg-amber-400 animate-pulse', label: 'Warn — CPU > 60% or latency > 30ms' },
            { dot: 'bg-red-500 animate-pulse', label: 'Critical — CPU > 80% or latency > 100ms or lag > 30s' },
          ].map(l => (
            <div key={l.label} className="flex items-center gap-1.5">
              <span className={`w-2 h-2 rounded-full ${l.dot}`} />
              <span className="text-[10px] text-gray-700 dark:text-gray-400">{l.label}</span>
            </div>
          ))}
        </div>
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

// ── Slow Queries Tab ──────────────────────────────────────────────────────────

function SlowQueriesTab({ slowQueries }: { slowQueries: SlowQueriesResponse | null }) {
  if (!slowQueries) {
    return (
      <div className="text-center py-20 bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50">
        <i className="fas fa-spinner fa-spin text-gray-400 text-2xl mb-3" />
        <p className="text-sm text-gray-600 dark:text-gray-400">Loading slow query data…</p>
      </div>
    );
  }

  if (!slowQueries.logsEnabled) {
    return (
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50 p-8 text-center">
        <div className="w-14 h-14 bg-amber-50 dark:bg-amber-900/20 rounded-full flex items-center justify-center mx-auto mb-4">
          <i className="fas fa-exclamation-triangle text-amber-500 text-xl" />
        </div>
        <h3 className="text-base font-semibold text-gray-800 dark:text-gray-100 mb-2">Slow Query Logs Not Enabled</h3>
        <p className="text-sm text-gray-600 dark:text-gray-400 mb-4 max-w-lg mx-auto">
          {slowQueries.message ?? 'CloudWatch Logs export is not configured for slow queries on any RDS instance in this account.'}
        </p>
        <div className="inline-flex flex-col gap-1.5 text-left bg-gray-50 dark:bg-gray-700/40 rounded-xl px-5 py-4 text-xs text-gray-700 dark:text-gray-300 max-w-sm">
          <p className="font-semibold text-gray-800 dark:text-gray-200 mb-1">How to enable:</p>
          <p>1. Go to <strong>RDS → Parameter Groups</strong> and set <code className="bg-gray-200 dark:bg-gray-600 px-1 rounded">slow_query_log = 1</code></p>
          <p>2. In the <strong>RDS instance</strong>, enable <em>CloudWatch Logs export → Slow query</em></p>
          <p>3. Logs will appear within a few minutes</p>
        </div>
      </div>
    );
  }

  if (slowQueries.queries.length === 0) {
    return (
      <div className="text-center py-20 bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50">
        <i className="fas fa-check-circle text-emerald-500 text-3xl mb-3" />
        <h3 className="text-base font-semibold text-gray-800 dark:text-gray-100 mb-1">No Slow Queries</h3>
        <p className="text-sm text-gray-600 dark:text-gray-400">No queries exceeding the slow query threshold in the last 7 days.</p>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      {/* Summary strip */}
      <div className="flex items-center gap-3 p-4 bg-amber-50 dark:bg-amber-900/20 border border-amber-200 dark:border-amber-800 rounded-xl">
        <i className="fas fa-tachometer-alt text-amber-500" />
        <p className="text-sm text-amber-800 dark:text-amber-300 font-medium">
          {slowQueries.queries.length} slow {slowQueries.queries.length === 1 ? 'query' : 'queries'} found in the last 7 days — sorted by query time (slowest first)
        </p>
      </div>

      {/* Table */}
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
                {['Instance', 'Query Time (s)', 'Lock Time (s)', 'Rows Examined', 'Rows Sent', 'Timestamp', 'Query'].map(h => (
                  <th key={h} className="text-left px-3 py-2.5 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {slowQueries.queries.map((q, i) => (
                <tr key={i} className="border-b border-gray-50 dark:border-gray-700/50 hover:bg-gray-50/50 dark:hover:bg-gray-700/20 transition-colors align-top">
                  <td className="px-3 py-3">
                    <p className="font-medium text-gray-800 dark:text-gray-200 text-xs whitespace-nowrap">{q.instanceId}</p>
                    <p className="text-[10px] text-gray-600 dark:text-gray-400">{q.region}</p>
                  </td>
                  <td className={`px-3 py-3 tabular-nums font-semibold text-xs ${q.queryTimeSeconds > 10 ? 'text-red-600 dark:text-red-400' : q.queryTimeSeconds > 3 ? 'text-amber-600 dark:text-amber-400' : 'text-gray-800 dark:text-gray-300'}`}>
                    {q.queryTimeSeconds.toFixed(3)}
                  </td>
                  <td className="px-3 py-3 tabular-nums text-xs text-gray-800 dark:text-gray-300">
                    {q.lockTimeSeconds > 0 ? q.lockTimeSeconds.toFixed(3) : '—'}
                  </td>
                  <td className="px-3 py-3 tabular-nums text-xs text-gray-800 dark:text-gray-300">
                    {q.rowsExamined.toLocaleString()}
                  </td>
                  <td className="px-3 py-3 tabular-nums text-xs text-gray-800 dark:text-gray-300">
                    {q.rowsSent.toLocaleString()}
                  </td>
                  <td className="px-3 py-3 text-[11px] text-gray-600 dark:text-gray-400 whitespace-nowrap">
                    {q.queryTimestamp ? new Date(q.queryTimestamp).toLocaleString() : '—'}
                  </td>
                  <td className="px-3 py-3 max-w-sm">
                    <code className="block text-[11px] bg-gray-50 dark:bg-gray-700/50 text-gray-800 dark:text-gray-300 rounded px-2 py-1 font-mono overflow-x-auto whitespace-pre-wrap break-words">
                      {q.query}
                    </code>
                    {q.rowsExamined > 0 && q.rowsSent > 0 && q.rowsExamined / q.rowsSent > 100 && (
                      <span className="inline-flex mt-1 items-center gap-1 text-[10px] text-orange-600 dark:text-orange-400">
                        <i className="fas fa-exclamation-triangle" />
                        High rows-examined/rows-sent ratio — possible missing index
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="px-5 py-3 border-t border-gray-100 dark:border-gray-700/50 bg-gray-50/50 dark:bg-gray-700/20 text-[11px] text-gray-600 dark:text-gray-400">
          Showing top {slowQueries.queries.length} slow queries from CloudWatch Logs over the last 7 days.
          A high rows-examined/rows-sent ratio (&gt;100×) suggests a missing index.
        </div>
      </div>
    </div>
  );
}

// ── Agent Query Stats Tab ─────────────────────────────────────────────────────

function AgentStatsTab({ stats, accountId }: { stats: AgentQueryStat[]; accountId: string | null }) {
  const [apiKey, setApiKey]         = React.useState<string>('');
  const [dsn, setDsn]               = React.useState('');
  const [clusterName, setClusterName] = React.useState('db-agent-prod');
  const [copied, setCopied]         = React.useState(false);

  React.useEffect(() => {
    fetch('/api/v1/agent/install-info', { credentials: 'include' })
      .then(r => r.json())
      .then(d => setApiKey(d.apiKey ?? ''))
      .catch(() => {});
  }, []);

  const serverUrl = window.location.origin;
  const dsnPlaceholder = 'xamops:password@tcp(your-rds-host:3306)/yourdb';

  const command = [
    `XAMOPS_SERVER_URL="${serverUrl}"`,
    `XAMOPS_API_KEY="${apiKey || '<api-key>'}"`,
    `CLOUD_ACCOUNT_ID="${accountId || '<account-id>'}"`,
    `CLUSTER_NAME="${clusterName}"`,
    `COLLECTOR_DB=true`,
    `DB_DSN_0="${dsn || dsnPlaceholder}"`,
    `./xamops-agent`,
  ].join(' \\\n');

  const handleCopy = () => {
    navigator.clipboard.writeText(command).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  if (stats.length === 0) {
    return (
      <div className="max-w-2xl mx-auto space-y-5 py-4">
        {/* Header */}
        <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50 p-6 text-center">
          <div className="w-12 h-12 bg-blue-50 dark:bg-blue-900/20 rounded-full flex items-center justify-center mx-auto mb-3">
            <i className="fas fa-database text-blue-400 text-lg" />
          </div>
          <h3 className="text-base font-semibold text-gray-800 dark:text-gray-100 mb-1">Connect Your Database</h3>
          <p className="text-sm text-gray-500 dark:text-gray-400">
            Run the XamOps agent inside your VPC to collect live query stats from RDS.
          </p>
        </div>

        {/* Step 1 — Create DB user */}
        <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50 p-5">
          <p className="text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-3">Step 1 — Create read-only DB user</p>
          <div className="space-y-3">
            <div>
              <p className="text-xs text-gray-600 dark:text-gray-400 mb-1.5 font-medium">MySQL / Aurora MySQL:</p>
              <pre className="bg-gray-900 text-green-400 rounded-lg px-4 py-3 text-[11px] leading-relaxed overflow-x-auto">{`CREATE USER 'xamops'@'%' IDENTIFIED BY 'choose-a-password';
GRANT SELECT ON performance_schema.* TO 'xamops'@'%';
FLUSH PRIVILEGES;`}</pre>
            </div>
            <div>
              <p className="text-xs text-gray-600 dark:text-gray-400 mb-1.5 font-medium">PostgreSQL / Aurora Postgres:</p>
              <pre className="bg-gray-900 text-green-400 rounded-lg px-4 py-3 text-[11px] leading-relaxed overflow-x-auto">{`CREATE USER xamops WITH PASSWORD 'choose-a-password';
GRANT pg_read_all_stats TO xamops;`}</pre>
            </div>
          </div>
        </div>

        {/* Step 2 — Configure */}
        <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50 p-5">
          <p className="text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider mb-3">Step 2 — Enter your database connection</p>
          <div className="space-y-3">
            <div>
              <label className="block text-xs font-medium text-gray-700 dark:text-gray-300 mb-1">
                Agent name <span className="text-gray-400">(any label you choose)</span>
              </label>
              <input
                type="text"
                value={clusterName}
                onChange={e => setClusterName(e.target.value)}
                className="w-full px-3 py-2 text-sm rounded-lg border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-800 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-blue-500"
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-gray-700 dark:text-gray-300 mb-1">
                DB connection string <span className="text-red-500">*</span>
              </label>
              <input
                type="text"
                value={dsn}
                onChange={e => setDsn(e.target.value)}
                placeholder={dsnPlaceholder}
                className="w-full px-3 py-2 text-sm rounded-lg border border-gray-200 dark:border-gray-600 bg-white dark:bg-gray-700 text-gray-800 dark:text-gray-100 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-blue-500 font-mono"
              />
              <p className="text-[11px] text-gray-500 dark:text-gray-400 mt-1">
                MySQL: <code className="bg-gray-100 dark:bg-gray-700 px-1 rounded">user:pass@tcp(host:3306)/db</code>
                &nbsp;· Postgres: <code className="bg-gray-100 dark:bg-gray-700 px-1 rounded">postgres://user:pass@host:5432/db?sslmode=require</code>
              </p>
            </div>
          </div>
        </div>

        {/* Step 3 — Generated command */}
        <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50 p-5">
          <div className="flex items-center justify-between mb-3">
            <p className="text-xs font-bold text-gray-500 dark:text-gray-400 uppercase tracking-wider">Step 3 — Run on your EC2</p>
            <button
              onClick={handleCopy}
              className="flex items-center gap-1.5 text-xs px-3 py-1.5 rounded-lg bg-blue-50 dark:bg-blue-900/30 text-blue-700 dark:text-blue-300 hover:bg-blue-100 dark:hover:bg-blue-900/50 transition-colors"
            >
              <i className={`fas ${copied ? 'fa-check' : 'fa-copy'} text-[10px]`} />
              {copied ? 'Copied!' : 'Copy'}
            </button>
          </div>
          <pre className="bg-gray-900 text-green-400 rounded-lg px-4 py-3 text-[11px] leading-relaxed overflow-x-auto whitespace-pre">{command}</pre>
          <p className="text-[11px] text-gray-500 dark:text-gray-400 mt-2">
            The agent must run inside the same VPC as your RDS. After running, data appears here within 5 minutes.
          </p>
        </div>
      </div>
    );
  }

  const fmtMs = (s: number) => s < 1 ? `${(s * 1000).toFixed(1)}ms` : `${s.toFixed(2)}s`;

  return (
    <div className="space-y-4">
      <div className="flex items-center gap-3 p-4 bg-blue-50 dark:bg-blue-900/20 border border-blue-200 dark:border-blue-800 rounded-xl">
        <i className="fas fa-database text-blue-500" />
        <p className="text-sm text-blue-800 dark:text-blue-300 font-medium">
          {stats.length} queries from agent — sorted by total execution time (heaviest first)
        </p>
      </div>
      <div className="bg-white/90 dark:bg-gray-800/80 backdrop-blur-sm rounded-2xl border border-gray-100 dark:border-gray-700/50 overflow-hidden">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-gray-100 dark:border-gray-700/50 bg-gray-50/80 dark:bg-gray-700/30">
                {['DB', 'Calls', 'Total Time', 'Avg Time', 'Max Time', 'Rows Examined', 'Rows Sent', 'Query'].map(h => (
                  <th key={h} className="text-left px-3 py-2.5 text-[10px] font-bold text-gray-700 dark:text-gray-400 uppercase tracking-wider whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-50 dark:divide-gray-700/30">
              {stats.map((s, i) => (
                <tr key={i} className="hover:bg-gray-50/50 dark:hover:bg-gray-700/20 transition-colors">
                  <td className="px-3 py-3 whitespace-nowrap">
                    <span className={`inline-flex items-center gap-1.5 text-xs font-medium px-2 py-0.5 rounded-full ${s.dbEngine === 'mysql' ? 'bg-orange-100 text-orange-700 dark:bg-orange-900/30 dark:text-orange-400' : 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400'}`}>
                      <i className={`fas ${s.dbEngine === 'mysql' ? 'fa-leaf' : 'fa-elephant'} text-[9px]`} />
                      {s.dsnAlias}
                    </span>
                  </td>
                  <td className="px-3 py-3 tabular-nums text-gray-800 dark:text-gray-300">{s.callCount.toLocaleString()}</td>
                  <td className="px-3 py-3 tabular-nums font-semibold text-gray-800 dark:text-gray-200">{fmtMs(s.totalTimeSeconds)}</td>
                  <td className={`px-3 py-3 tabular-nums ${s.avgTimeSeconds > 1 ? 'text-red-600 dark:text-red-400 font-semibold' : s.avgTimeSeconds > 0.1 ? 'text-amber-600 dark:text-amber-400' : 'text-gray-800 dark:text-gray-300'}`}>
                    {fmtMs(s.avgTimeSeconds)}
                  </td>
                  <td className="px-3 py-3 tabular-nums text-gray-800 dark:text-gray-300">{fmtMs(s.maxTimeSeconds)}</td>
                  <td className="px-3 py-3 tabular-nums text-gray-800 dark:text-gray-300">{s.rowsExamined.toLocaleString()}</td>
                  <td className="px-3 py-3 tabular-nums text-gray-800 dark:text-gray-300">{s.rowsSent.toLocaleString()}</td>
                  <td className="px-3 py-3 max-w-xs">
                    <code className="text-[11px] text-gray-700 dark:text-gray-300 break-words line-clamp-2">{s.queryDigest}</code>
                    {s.noIndexUsed && (
                      <span className="mt-1 flex items-center gap-1 text-[10px] text-amber-600 dark:text-amber-400">
                        <i className="fas fa-exclamation-triangle" />
                        No index used
                      </span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="px-5 py-3 border-t border-gray-100 dark:border-gray-700/50 bg-gray-50/50 dark:bg-gray-700/20 text-[11px] text-gray-600 dark:text-gray-400">
          Data from <code className="bg-gray-200 dark:bg-gray-600 px-1 rounded">performance_schema</code> /
          <code className="bg-gray-200 dark:bg-gray-600 px-1 rounded ml-1">pg_stat_statements</code> — collected by XamOps DB agent every 5 min.
          Avg time &gt; 1s is highlighted red. Queries with no index usage should be reviewed.
        </div>
      </div>
    </div>
  );
}

// ── Page ──────────────────────────────────────────────────────────────────────

const DataOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const { setTitle, setActions, clearActions } = useTopbar();
  const [tab, setTab] = useState<Tab>('overview');

  const accountId = selectedAccountIds[0] ?? null;
  const {
    summary, inventory, metrics, costs, idle, rightsizing, snapshots, security, slowQueries,
    agentQueryStats,
    loading, error, refresh,
  } = useDbOpsData(accountId);

  React.useEffect(() => {
    setTitle('DbOps');
    return () => setTitle('');
  }, [setTitle]);

  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-2">
        {/* Live status pill */}
        {accountId && !loading && (
          <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-emerald-50 dark:bg-emerald-900/20 border border-emerald-200 dark:border-emerald-800 text-emerald-700 dark:text-emerald-400 text-xs font-semibold">
            <span className="relative flex h-2 w-2">
              <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75" />
              <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500" />
            </span>
            Live
          </div>
        )}
        {/* DB spend pill */}
        {summary && (
          <div className="hidden sm:flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-sky-50 dark:bg-sky-900/20 border border-sky-200 dark:border-sky-800 text-sky-700 dark:text-sky-400 text-xs font-semibold">
            <i className="fas fa-database text-[10px]" />
            {fmt$(summary.totalDbMonthlyCost)}/mo
          </div>
        )}
        {/* Savings pill */}
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
    return () => clearActions();
  }, [setActions, clearActions, accountId, summary, loading, refresh]);

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

          {/* Content */}
          {accountId && !loading && (
            <>
              {summary && <SummaryCards s={summary} />}
              <TabBar
                active={tab}
                onChange={setTab}
                tabs={[
                  { id: 'overview',     label: 'Overview',       icon: 'fas fa-chart-pie' },
                  { id: 'performance',  label: 'Performance',    icon: 'fas fa-heartbeat', badge: Object.values(metrics).filter(m => instanceHealth(m) === 'critical').length },
                  { id: 'slowqueries',  label: 'Slow Queries',   icon: 'fas fa-stopwatch',  badge: slowQueries?.queries?.length ?? 0 },
                  { id: 'agentstats',   label: 'Agent Stats',    icon: 'fas fa-database',   badge: agentQueryStats.length },
                  { id: 'inventory',    label: 'Inventory',      icon: 'fas fa-server',      badge: inventory.length },
                  { id: 'cost',         label: 'Cost & Savings', icon: 'fas fa-dollar-sign', badge: idle.length + rightsizing.length },
                  { id: 'security',     label: 'Security',       icon: 'fas fa-shield-alt',  badge: security.filter(c => c.status === 'FAIL').length },
                  { id: 'snapshots',    label: 'Snapshots',      icon: 'fas fa-camera',      badge: snapshots.length },
                ]}
              />
              {tab === 'overview'    && <OverviewTab summary={summary} idle={idle} rightsizing={rightsizing} />}
              {tab === 'performance' && <PerformanceTab inventory={inventory} metrics={metrics} />}
              {tab === 'slowqueries' && <SlowQueriesTab slowQueries={slowQueries} />}
              {tab === 'agentstats'  && <AgentStatsTab stats={agentQueryStats} accountId={accountId} />}
              {tab === 'inventory'   && <InventoryTab inventory={inventory} />}
              {tab === 'cost'        && <CostTab costs={costs} idle={idle} rightsizing={rightsizing} />}
              {tab === 'security'    && <SecurityTab security={security} />}
              {tab === 'snapshots'   && <SnapshotsTab snapshots={snapshots} />}
            </>
          )}

      </div>
    </main>
  );
};

export default DataOpsPage;
