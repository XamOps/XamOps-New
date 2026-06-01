/**
 * EksAlertsTab
 *
 * Shows aggregated security/health alerts from all EKS clusters.
 * Clicking a row navigates to the EKS details page at the relevant tab.
 */

import React, { useState, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import { Icon } from '@iconify/react';
import type { EksAlertItem, EksAlertSeverity, EksAlertSource } from '../../hooks/useEksAlertsData';
import { useEksAlertsData } from '../../hooks/useEksAlertsData';
import { useAccounts } from '../../contexts/AccountContext';

// ── helpers ────────────────────────────────────────────────────────────────
const SEV_STYLES: Record<EksAlertSeverity, { bg: string; text: string; dot: string }> = {
  critical: { bg: '#ffebe9', text: '#cf222e', dot: '#cf222e' },
  high:     { bg: '#fff8c5', text: '#9a6700', dot: '#d97706' },
  medium:   { bg: '#ddf4ff', text: '#0550ae', dot: '#2f81f7' },
  low:      { bg: '#dafbe1', text: '#1a7f37', dot: '#1a7f37' },
  info:     { bg: 'rgba(0,0,0,0.04)', text: 'rgba(0,0,0,0.5)', dot: 'rgba(0,0,0,0.3)' },
};

const SOURCE_META: Record<EksAlertSource, { label: string; icon: string; color: string }> = {
  falco:     { label: 'Falco Runtime', icon: 'mdi:shield-half-full',       color: 'text-red-600' },
  kubescape: { label: 'Compliance',    icon: 'mdi:clipboard-check-outline', color: 'text-amber-600' },
  trivy:     { label: 'Image Scan',    icon: 'mdi:virus-outline',           color: 'text-orange-600' },
  events:    { label: 'K8s Events',   icon: 'mdi:alert-circle',            color: 'text-rose-500' },
  nodes:     { label: 'Nodes',        icon: 'mdi:server',                  color: 'text-indigo-600' },
  pods:      { label: 'Pods',         icon: 'mdi:cube-outline',            color: 'text-cyan-600' },
  karpenter: { label: 'Karpenter',    icon: 'mdi:rocket-launch-outline',   color: 'text-purple-600' },
};

const TAB_LABELS: Record<string, string> = {
  security:    'Security',
  overview:    'Overview',
  nodes:       'Nodes',
  pods:        'Pods',
  karpenter:   'Karpenter',
  cost:        'Cost',
  performance: 'Performance',
  workloads:   'Workloads',
  containers:  'Containers',
  resources:   'Resources',
};

const CloudSVG = () => (
  <svg className="absolute right-2 bottom-2 opacity-[0.07]" width="80" height="60" viewBox="0 0 80 60">
    <path d="M65 48H20a18 18 0 010-36c1 0 2 0 3 .1A24 24 0 0168 28h-.5A16 16 0 0165 48z" fill="currentColor" />
  </svg>
);

const BEBAS = {
  fontFamily: "'Bebas Neue', 'Barlow Condensed', sans-serif",
  fontSize: '32px',
  fontWeight: 400,
  lineHeight: '32px',
  fontVariantNumeric: 'tabular-nums' as const,
};

interface KpiCardProps {
  label: string;
  value: number;
  icon: string;
  iconBg: string;
  iconColor: string;
  valueColor: string;
}

const KpiCard: React.FC<KpiCardProps> = ({ label, value, icon, iconBg, iconColor, valueColor }) => (
  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm px-5 py-4 flex items-center gap-4 relative overflow-hidden">
    <CloudSVG />
    <div className={`w-10 h-10 rounded-lg flex items-center justify-center flex-shrink-0 ${iconBg}`}>
      <Icon icon={icon} width={18} className={iconColor} />
    </div>
    <div>
      <div className="text-[12px] font-semibold uppercase tracking-[0.09em] leading-[15px] text-[rgba(0,0,0,0.5)]">{label}</div>
      <div style={{ ...BEBAS, color: valueColor }}>{value}</div>
    </div>
  </div>
);

// ── main component ─────────────────────────────────────────────────────────
const EksAlertsTab: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const accountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const navigate = useNavigate();

  const { alerts, loading, error, counts, refresh } = useEksAlertsData({ accountId });

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
        <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-4">
          <Icon icon="mdi:bell-off-outline" width={28} className="text-[rgba(0,0,0,0.3)]" />
        </div>
        <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">No Account Selected</p>
        <p className="text-[13px] text-[rgba(0,0,0,0.5)] mt-1">Select an account from the sidebar to view EKS alerts.</p>
      </div>
    );
  }

  return (
    <div className="space-y-5">

      {/* KPI strip */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        <KpiCard label="Total Alerts" value={counts.total}    icon="mdi:format-list-bulleted"  iconBg="bg-indigo-50"  iconColor="text-indigo-600" valueColor="rgb(10,10,10)" />
        <KpiCard label="Critical"     value={counts.critical} icon="mdi:alert-circle"           iconBg="bg-red-50"     iconColor="text-red-600"    valueColor="rgb(220,38,38)" />
        <KpiCard label="High"         value={counts.high}     icon="mdi:alert"                  iconBg="bg-amber-50"   iconColor="text-amber-600"  valueColor="rgb(217,119,6)" />
        <KpiCard label="Medium"       value={counts.medium}   icon="mdi:information"            iconBg="bg-blue-50"    iconColor="text-blue-600"   valueColor="rgb(37,99,235)" />
      </div>

      {/* Filters */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-3 flex flex-col sm:flex-row gap-3 flex-wrap">
        <div className="relative flex-1 min-w-[200px]">
          <Icon icon="mdi:magnify" width={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-[rgba(0,0,0,0.4)]" />
          <input
            type="text"
            placeholder="Search alerts..."
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="w-full pl-9 pr-4 py-2 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
          />
        </div>

        <select
          value={severityFilter}
          onChange={e => setSeverityFilter(e.target.value as any)}
          className="text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
        >
          <option value="all">All Severities</option>
          <option value="critical">Critical</option>
          <option value="high">High</option>
          <option value="medium">Medium</option>
          <option value="low">Low</option>
        </select>

        <select
          value={sourceFilter}
          onChange={e => setSourceFilter(e.target.value as any)}
          className="text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
        >
          <option value="all">All Sources</option>
          {(Object.keys(SOURCE_META) as EksAlertSource[]).map(s => (
            <option key={s} value={s}>{SOURCE_META[s].label}</option>
          ))}
        </select>

        {clusters.length > 2 && (
          <select
            value={clusterFilter}
            onChange={e => setClusterFilter(e.target.value)}
            className="text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg px-3 py-2 focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
          >
            {clusters.map(c => (
              <option key={c} value={c}>{c === 'all' ? 'All Clusters' : c}</option>
            ))}
          </select>
        )}

        <button
          onClick={refresh}
          className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white text-xs font-medium rounded-md hover:bg-black transition-colors ml-auto disabled:opacity-50"
          disabled={loading}
        >
          <Icon icon="mdi:refresh" width={14} className={loading ? 'animate-spin' : ''} />
          Refresh
        </button>
      </div>

      {/* Error */}
      {error && (
        <div className="bg-red-50 border border-red-100 rounded-lg p-4 flex items-center gap-3">
          <Icon icon="mdi:alert-circle" width={16} className="text-red-600 shrink-0" />
          <p className="text-sm text-red-700">{error}</p>
        </div>
      )}

      {/* Table */}
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">

        {/* Loading skeleton */}
        {loading && alerts.length === 0 && (
          <div className="p-6 space-y-3">
            {[1, 2, 3, 4, 5].map(i => (
              <div key={i} className="h-14 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />
            ))}
          </div>
        )}

        {/* Empty state */}
        {!loading && filtered.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16 text-center">
            <div className="w-14 h-14 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mb-4">
              <Icon icon="mdi:check-circle-outline" width={28} className="text-emerald-600" />
            </div>
            <p className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">No alerts found</p>
            <p className="text-[13px] text-[rgba(0,0,0,0.5)] mt-1">
              {alerts.length > 0 ? 'Try clearing the filters.' : 'All EKS clusters look healthy, or no clusters have been synced yet.'}
            </p>
          </div>
        )}

        {/* Rows */}
        {filtered.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)] w-24">Severity</th>
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)] w-32">Source</th>
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)]">Alert</th>
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)] w-40">Cluster</th>
                  <th className="px-4 py-3 text-[11px] font-semibold uppercase tracking-wider text-[rgba(0,0,0,0.4)] w-28">Tab</th>
                  <th className="px-4 py-3 w-10"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-[rgba(0,0,0,0.04)]">
                {filtered.map(alert => {
                  const sev = SEV_STYLES[alert.severity];
                  const src = SOURCE_META[alert.source];
                  return (
                    <tr
                      key={alert.id}
                      onClick={() => handleAlertClick(alert)}
                      className="hover:bg-[rgba(0,0,0,0.025)] transition-colors cursor-pointer group"
                      title={`Click to open ${alert.clusterName} → ${TAB_LABELS[alert.targetTab] || alert.targetTab} tab`}
                    >
                      {/* Severity */}
                      <td className="px-4 py-3">
                        <span
                          className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold border"
                          style={{ background: sev.bg, color: sev.text, borderColor: sev.text + '44' }}
                        >
                          <span className="w-1.5 h-1.5 rounded-full flex-shrink-0" style={{ background: sev.dot }} />
                          {alert.severity.charAt(0).toUpperCase() + alert.severity.slice(1)}
                        </span>
                      </td>

                      {/* Source */}
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-1.5">
                          <Icon icon={src.icon} width={13} className={src.color} />
                          <span className="text-[12px] font-medium text-[rgba(0,0,0,0.5)]">{src.label}</span>
                        </div>
                      </td>

                      {/* Alert title + message */}
                      <td className="px-4 py-3">
                        <div className="font-semibold text-[13px] text-[rgba(0,0,0,0.88)] mb-0.5 group-hover:text-indigo-600 transition-colors">
                          {alert.title}
                          {(alert.count || 0) > 1 && (
                            <span className="ml-2 text-[10px] font-semibold text-[rgba(0,0,0,0.4)]">×{alert.count}</span>
                          )}
                        </div>
                        <div className="text-[11px] text-[rgba(0,0,0,0.5)] truncate max-w-[420px]">{alert.message}</div>
                        {alert.namespace && (
                          <span className="inline-block mt-1 text-[10px] bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.06)] rounded px-1.5 py-0.5 text-[rgba(0,0,0,0.5)] font-mono">{alert.namespace}</span>
                        )}
                      </td>

                      {/* Cluster name */}
                      <td className="px-4 py-3">
                        <div className="text-[12px] font-semibold text-[rgba(0,0,0,0.88)] truncate max-w-[140px]" title={alert.clusterName}>
                          {alert.clusterName}
                        </div>
                        <div className="text-[10px] text-[rgba(0,0,0,0.4)]">{alert.region}</div>
                      </td>

                      {/* Target tab */}
                      <td className="px-4 py-3">
                        <span className="inline-flex items-center gap-1 text-[11px] font-semibold text-indigo-600 bg-indigo-50 border border-indigo-100 rounded px-2 py-0.5">
                          <Icon icon="mdi:arrow-right" width={10} />
                          {TAB_LABELS[alert.targetTab] || alert.targetTab}
                        </span>
                      </td>

                      {/* Chevron */}
                      <td className="px-4 py-3 text-right">
                        <Icon icon="mdi:chevron-right" width={16} className="text-[rgba(0,0,0,0.3)] group-hover:text-indigo-600 transition-colors" />
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>

            <div className="px-4 py-3 bg-[rgba(0,0,0,0.02)] border-t border-[rgba(0,0,0,0.06)] text-[11px] text-[rgba(0,0,0,0.4)]">
              Showing {filtered.length} of {alerts.length} alerts — click any row to open the cluster at the relevant tab
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default EksAlertsTab;
