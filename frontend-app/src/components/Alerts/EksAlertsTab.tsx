/**
 * EksAlertsTab
 *
 * Shows aggregated security/health alerts from all EKS clusters.
 * Clicking a row navigates to the EKS details page at the relevant tab.
 */

import React, { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import type { EksAlertItem, EksAlertSeverity, EksAlertSource } from '../../hooks/useEksAlertsData';
import { useEksAlertsData } from '../../hooks/useEksAlertsData';
import { useAccounts } from '../../contexts/AccountContext';

// ── helpers ────────────────────────────────────────────────────────────────
const SEV_STYLES: Record<EksAlertSeverity, { bg: string; text: string; dot: string; badge: string }> = {
  critical: { bg: '#ffebe9', text: '#cf222e', dot: '#cf222e', badge: 'bg-red-100 text-red-700 border-red-200' },
  high:     { bg: '#fff8c5', text: '#9a6700', dot: '#d97706', badge: 'bg-amber-100 text-amber-700 border-amber-200' },
  medium:   { bg: '#ddf4ff', text: '#0550ae', dot: '#2f81f7', badge: 'bg-blue-100 text-blue-700 border-blue-200' },
  low:      { bg: '#dafbe1', text: '#1a7f37', dot: '#1a7f37', badge: 'bg-green-100 text-green-700 border-green-200' },
  info:     { bg: '#f6f8fa', text: '#57606a', dot: '#8b949e', badge: 'bg-gray-100 text-gray-600 border-gray-200' },
};

const SOURCE_META: Record<EksAlertSource, { label: string; icon: string; color: string }> = {
  falco:     { label: 'Falco Runtime', icon: 'fa-shield-halved', color: 'text-red-600' },
  kubescape: { label: 'Compliance',    icon: 'fa-clipboard-check', color: 'text-amber-600' },
  trivy:     { label: 'Image Scan',    icon: 'fa-virus', color: 'text-orange-600' },
  events:    { label: 'K8s Events',   icon: 'fa-circle-exclamation', color: 'text-rose-500' },
  nodes:     { label: 'Nodes',        icon: 'fa-server', color: 'text-indigo-600' },
  pods:      { label: 'Pods',         icon: 'fa-box', color: 'text-cyan-600' },
  karpenter: { label: 'Karpenter',    icon: 'fa-rocket', color: 'text-purple-600' },
};

const TAB_LABELS: Record<string, string> = {
  security:  'Security',
  overview:  'Overview',
  nodes:     'Nodes',
  pods:      'Pods',
  karpenter: 'Karpenter',
  cost:      'Cost',
  performance: 'Performance',
  workloads: 'Workloads',
  containers:'Containers',
  resources: 'Resources',
};

interface KpiCardProps {
  label: string;
  value: number;
  icon: string;
  color: string;
  bg: string;
}

const KpiCard: React.FC<KpiCardProps> = ({ label, value, icon, color, bg }) => (
  <div className="bg-white border border-[#d0d7de] rounded-lg px-5 py-4 flex items-center gap-4">
    <div className={`w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 ${bg}`}>
      <i className={`fas ${icon} ${color}`}></i>
    </div>
    <div>
      <div className="text-[11px] font-medium uppercase tracking-wider text-[#8b949e]">{label}</div>
      <div className="text-[26px] font-semibold leading-none text-[#1a1f2e] mt-0.5">{value}</div>
    </div>
  </div>
);

// ── main component ─────────────────────────────────────────────────────────
const EksAlertsTab: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const accountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const navigate = useNavigate();

  const { alerts, loading, error, counts, refresh } = useEksAlertsData({ accountId });

  // filters
  const [severityFilter, setSeverityFilter] = useState<EksAlertSeverity | 'all'>('all');
  const [sourceFilter, setSourceFilter] = useState<EksAlertSource | 'all'>('all');
  const [clusterFilter, setClusterFilter] = useState<string>('all');
  const [search, setSearch] = useState('');

  const clusters = useMemo(() => ['all', ...Array.from(new Set(alerts.map(a => a.clusterName)))], [alerts]);

  const filtered = useMemo(() => {
    let result = alerts;
    if (severityFilter !== 'all') result = result.filter(a => a.severity === severityFilter);
    if (sourceFilter !== 'all') result = result.filter(a => a.source === sourceFilter);
    if (clusterFilter !== 'all') result = result.filter(a => a.clusterName === clusterFilter);
    if (search) {
      const q = search.toLowerCase();
      result = result.filter(a =>
        a.title.toLowerCase().includes(q) ||
        a.message.toLowerCase().includes(q) ||
        a.clusterName.toLowerCase().includes(q) ||
        (a.namespace || '').toLowerCase().includes(q)
      );
    }
    return result;
  }, [alerts, severityFilter, sourceFilter, clusterFilter, search]);

  const handleAlertClick = (alert: EksAlertItem) => {
    const url = `/eks-details?name=${encodeURIComponent(alert.clusterName)}&region=${encodeURIComponent(alert.region)}&accountIds=${encodeURIComponent(alert.accountId)}&tab=${alert.targetTab}`;
    navigate(url);
  };

  if (!accountId) {
    return (
      <div className="flex flex-col items-center justify-center py-20 text-center">
        <div className="w-16 h-16 bg-[#f6f8fa] border border-[#d0d7de] rounded-xl flex items-center justify-center mb-4">
          <i className="fas fa-bell-slash text-[#8b949e] text-2xl"></i>
        </div>
        <p className="text-sm font-semibold text-[#1a1f2e]">No Account Selected</p>
        <p className="text-[13px] text-[#57606a] mt-1">Select an account from the sidebar to view EKS alerts.</p>
      </div>
    );
  }

  return (
    <div className="space-y-5 p-5">

      {/* KPI strip */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <KpiCard label="Total Alerts" value={counts.total}    icon="fa-list"              color="text-[#2f4bde]" bg="bg-blue-50" />
        <KpiCard label="Critical"     value={counts.critical} icon="fa-exclamation-circle" color="text-red-600"   bg="bg-red-50" />
        <KpiCard label="High"         value={counts.high}     icon="fa-triangle-exclamation" color="text-amber-600" bg="bg-amber-50" />
        <KpiCard label="Medium"       value={counts.medium}   icon="fa-info-circle"        color="text-blue-600"  bg="bg-blue-50" />
      </div>

      {/* Filters */}
      <div className="bg-white border border-[#d0d7de] rounded-lg p-3 flex flex-col sm:flex-row gap-3 flex-wrap">
        {/* Search */}
        <div className="relative flex-1 min-w-[200px]">
          <i className="fas fa-search absolute left-3 top-1/2 -translate-y-1/2 text-[#8b949e] text-sm"></i>
          <input
            type="text"
            placeholder="Search alerts..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="w-full pl-9 pr-4 py-2 text-sm border border-[#d0d7de] rounded-lg focus:outline-none focus:ring-2 focus:ring-[#2f4bde] focus:border-transparent"
          />
        </div>

        {/* Severity filter */}
        <select
          value={severityFilter}
          onChange={e => setSeverityFilter(e.target.value as any)}
          className="text-sm border border-[#d0d7de] rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-[#2f4bde]"
        >
          <option value="all">All Severities</option>
          <option value="critical">Critical</option>
          <option value="high">High</option>
          <option value="medium">Medium</option>
          <option value="low">Low</option>
        </select>

        {/* Source filter */}
        <select
          value={sourceFilter}
          onChange={e => setSourceFilter(e.target.value as any)}
          className="text-sm border border-[#d0d7de] rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-[#2f4bde]"
        >
          <option value="all">All Sources</option>
          {(Object.keys(SOURCE_META) as EksAlertSource[]).map(s => (
            <option key={s} value={s}>{SOURCE_META[s].label}</option>
          ))}
        </select>

        {/* Cluster filter */}
        {clusters.length > 2 && (
          <select
            value={clusterFilter}
            onChange={e => setClusterFilter(e.target.value)}
            className="text-sm border border-[#d0d7de] rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-[#2f4bde]"
          >
            {clusters.map(c => (
              <option key={c} value={c}>{c === 'all' ? 'All Clusters' : c}</option>
            ))}
          </select>
        )}

        <button
          onClick={refresh}
          className="flex items-center gap-2 px-4 py-2 bg-[#2f4bde] text-white text-sm font-semibold rounded-lg hover:bg-[#2440c4] transition-all ml-auto"
          disabled={loading}
        >
          <i className={`fas fa-sync-alt text-xs ${loading ? 'animate-spin' : ''}`}></i>
          Refresh
        </button>
      </div>

      {/* Error */}
      {error && (
        <div className="bg-[#ffebe9] border border-[rgba(255,129,130,0.4)] rounded-lg p-4 flex items-center gap-3">
          <i className="fas fa-exclamation-circle text-[#cf222e]"></i>
          <p className="text-sm text-[#cf222e]">{error}</p>
        </div>
      )}

      {/* Table */}
      <div className="bg-white border border-[#d0d7de] rounded-lg overflow-hidden">

        {/* Loading skeleton */}
        {loading && alerts.length === 0 && (
          <div className="p-6 space-y-3">
            {[1, 2, 3, 4, 5].map(i => (
              <div key={i} className="h-14 bg-[#f6f8fa] rounded animate-pulse"></div>
            ))}
          </div>
        )}

        {/* Empty state */}
        {!loading && filtered.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 text-center">
            <div className="w-16 h-16 bg-[#dafbe1] border border-[rgba(74,194,107,0.4)] rounded-xl flex items-center justify-center mb-4">
              <i className="fas fa-check-circle text-[#1a7f37] text-2xl"></i>
            </div>
            <p className="text-sm font-semibold text-[#1a1f2e]">No alerts found</p>
            <p className="text-[13px] text-[#57606a] mt-1">
              {alerts.length > 0 ? 'Try clearing the filters.' : 'All EKS clusters look healthy, or no clusters have been synced yet.'}
            </p>
          </div>
        )}

        {/* Rows */}
        {filtered.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-[#f6f8fa] border-b border-[#e8ecf0]">
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[#8b949e] w-24">Severity</th>
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[#8b949e] w-32">Source</th>
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[#8b949e]">Alert</th>
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[#8b949e] w-40">Cluster</th>
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[#8b949e] w-28">Tab</th>
                  <th className="px-4 py-3 w-10"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[#e8ecf0]">
                {filtered.map(alert => {
                  const sev = SEV_STYLES[alert.severity];
                  const src = SOURCE_META[alert.source];
                  return (
                    <tr
                      key={alert.id}
                      onClick={() => handleAlertClick(alert)}
                      className="hover:bg-[#f6f8fa] transition-colors cursor-pointer group"
                      title={`Click to open ${alert.clusterName} → ${TAB_LABELS[alert.targetTab] || alert.targetTab} tab`}
                    >
                      {/* Severity */}
                      <td className="px-4 py-3">
                        <span
                          className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold border"
                          style={{ background: sev.bg, color: sev.text, borderColor: sev.text + '44' }}
                        >
                          <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: sev.dot }}></span>
                          {alert.severity.charAt(0).toUpperCase() + alert.severity.slice(1)}
                        </span>
                      </td>

                      {/* Source */}
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-1.5">
                          <i className={`fas ${src.icon} ${src.color} text-xs`}></i>
                          <span className="text-[12px] font-medium text-[#57606a]">{src.label}</span>
                        </div>
                      </td>

                      {/* Alert title + message */}
                      <td className="px-4 py-3">
                        <div className="font-semibold text-[13px] text-[#1a1f2e] mb-0.5 group-hover:text-[#2f4bde] transition-colors">
                          {alert.title}
                          {(alert.count || 0) > 1 && (
                            <span className="ml-2 text-[10px] font-semibold text-[#8b949e]">×{alert.count}</span>
                          )}
                        </div>
                        <div className="text-[11px] text-[#57606a] truncate max-w-[420px]">{alert.message}</div>
                        {alert.namespace && (
                          <span className="inline-block mt-1 text-[10px] bg-[#f6f8fa] border border-[#e8ecf0] rounded px-1.5 py-0.5 text-[#57606a] font-mono">{alert.namespace}</span>
                        )}
                      </td>

                      {/* Cluster name */}
                      <td className="px-4 py-3">
                        <div className="text-[12px] font-semibold text-[#1a1f2e] truncate max-w-[140px]" title={alert.clusterName}>
                          {alert.clusterName}
                        </div>
                        <div className="text-[10px] text-[#8b949e]">{alert.region}</div>
                      </td>

                      {/* Target tab */}
                      <td className="px-4 py-3">
                        <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-[#2f4bde] bg-blue-50 border border-blue-100 rounded px-2 py-0.5">
                          <i className="fas fa-arrow-right text-[9px]"></i>
                          {TAB_LABELS[alert.targetTab] || alert.targetTab}
                        </span>
                      </td>

                      {/* Chevron */}
                      <td className="px-4 py-3 text-right">
                        <i className="fas fa-chevron-right text-[#8b949e] text-xs group-hover:text-[#2f4bde] transition-colors"></i>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>

            <div className="px-4 py-3 bg-[#f6f8fa] border-t border-[#e8ecf0] text-[11px] text-[#8b949e]">
              Showing {filtered.length} of {alerts.length} alerts — click any row to open the cluster at the relevant tab
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default EksAlertsTab;
