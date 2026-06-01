import React, { useState } from 'react';
import { Icon } from '@iconify/react';
import type { AzureMonitorAlert } from '../../types/azureAlerts';
import SreInvestigateButton from '../SreInvestigateButton';

interface AzureAlertsTableProps {
  alerts: AzureMonitorAlert[];
  isLoading: boolean;
}

type StateFilter = 'All' | 'New' | 'Acknowledged' | 'Closed';

const STATE_FILTERS: StateFilter[] = ['All', 'New', 'Acknowledged', 'Closed'];

const SEVERITY_BADGE: Record<string, string> = {
  Critical:      'bg-red-100 text-red-700 border-red-200',
  Error:         'bg-orange-100 text-orange-700 border-orange-200',
  Warning:       'bg-amber-100 text-amber-700 border-amber-200',
  Informational: 'bg-blue-100 text-blue-700 border-blue-200',
  Verbose:       'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] border-[rgba(0,0,0,0.08)]',
};

const STATE_BADGE: Record<string, string> = {
  New:          'bg-red-100 text-red-700',
  Acknowledged: 'bg-blue-100 text-blue-700',
  Closed:       'bg-green-100 text-green-700',
};

const SkeletonRow = () => (
  <tr className="border-b border-[rgba(0,0,0,0.04)] animate-pulse">
    <td className="px-4 py-4"><div className="h-3 bg-[rgba(0,0,0,0.06)] rounded w-36"></div></td>
    <td className="px-4 py-4"><div className="h-5 bg-[rgba(0,0,0,0.06)] rounded w-20"></div></td>
    <td className="px-4 py-4"><div className="h-5 bg-[rgba(0,0,0,0.06)] rounded w-24"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-[rgba(0,0,0,0.06)] rounded w-16"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-[rgba(0,0,0,0.06)] rounded w-28"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-[rgba(0,0,0,0.06)] rounded w-24"></div></td>
    <td className="px-4 py-4"><div className="h-6 bg-[rgba(0,0,0,0.06)] rounded w-20"></div></td>
  </tr>
);

function formatTime(ts: string): string {
  if (!ts) return '—';
  try {
    return new Date(ts).toLocaleDateString('en-IN', {
      day: '2-digit', month: 'short', year: 'numeric',
      hour: '2-digit', minute: '2-digit',
    });
  } catch {
    return ts;
  }
}

const AzureAlertsTable: React.FC<AzureAlertsTableProps> = ({ alerts, isLoading }) => {
  const [activeFilter, setActiveFilter] = useState<StateFilter>('All');

  if (isLoading) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3 border-b border-[rgba(0,0,0,0.06)]">
          <div className="h-4 bg-[rgba(0,0,0,0.06)] rounded w-32 animate-pulse"></div>
        </div>
        <table className="w-full text-sm">
          <tbody>
            <SkeletonRow />
            <SkeletonRow />
            <SkeletonRow />
          </tbody>
        </table>
      </div>
    );
  }

  const filtered = activeFilter === 'All'
    ? alerts
    : alerts.filter(a => a.alertState === activeFilter);

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      {/* Header + filter tabs */}
      <div className="px-5 py-3 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between flex-wrap gap-3">
        <span className="text-[14px] font-semibold text-[rgba(0,0,0,0.88)]">Monitor Alerts</span>
        <div className="flex gap-1">
          {STATE_FILTERS.map(tab => {
            const count = tab === 'All' ? alerts.length : alerts.filter(a => a.alertState === tab).length;
            const isActive = activeFilter === tab;
            const activeColor = tab === 'New' ? 'bg-red-600'
              : tab === 'Acknowledged' ? 'bg-blue-600'
              : tab === 'Closed' ? 'bg-green-600'
              : 'bg-orange-600';
            return (
              <button
                key={tab}
                onClick={() => setActiveFilter(tab)}
                className={`px-3 py-1 text-xs font-semibold rounded-lg transition-all ${
                  isActive
                    ? `${activeColor} text-white shadow-sm`
                    : 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)] hover:bg-[rgba(0,0,0,0.07)]'
                }`}
              >
                {tab}
                {count > 0 && (
                  <span className={`ml-1.5 text-xs px-1 py-0.5 rounded-full ${
                    isActive ? 'bg-white/30 text-white' : 'bg-[rgba(0,0,0,0.08)] text-[rgba(0,0,0,0.5)]'
                  }`}>
                    {count}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>

      {filtered.length === 0 ? (
        <div className="py-16 text-center">
          <div className="w-14 h-14 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:check-circle-outline" width={28} className="text-emerald-500" />
          </div>
          <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">
            {activeFilter === 'All' ? 'No Alerts Detected' : `No ${activeFilter} Alerts`}
          </h3>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
            {activeFilter === 'All'
              ? 'Azure Monitor found no alerts for this subscription.'
              : `No alerts in ${activeFilter.toLowerCase()} state.`}
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-[rgba(0,0,0,0.02)]">
              <tr className="border-b border-[rgba(0,0,0,0.06)]">
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Alert Name</th>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Severity</th>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">State</th>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Signal</th>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Resource</th>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Fired</th>
                <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">SRE</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((alert, idx) => {
                const severityClass = SEVERITY_BADGE[alert.severityLabel] ?? SEVERITY_BADGE.Informational;
                const stateClass = STATE_BADGE[alert.alertState] ?? 'bg-[rgba(0,0,0,0.04)] text-[rgba(0,0,0,0.6)]';
                return (
                  <tr key={alert.alertId || idx} className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors">
                    <td className="px-4 py-3">
                      <div>
                        <span className="text-[13px] font-medium text-[rgba(0,0,0,0.88)] line-clamp-1 max-w-[200px] block" title={alert.alertName}>
                          {alert.alertName || '—'}
                        </span>
                        {alert.description && (
                          <span className="text-[12px] text-[rgba(0,0,0,0.4)] line-clamp-1 max-w-[200px] block" title={alert.description}>
                            {alert.description}
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold border ${severityClass}`}>
                        {alert.severityLabel || alert.severity || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${stateClass}`}>
                        {alert.alertState || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[11px] text-[rgba(0,0,0,0.6)] bg-[rgba(0,0,0,0.04)] px-2 py-0.5 rounded">
                        {alert.signalType || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[13px] text-[rgba(0,0,0,0.6)] truncate max-w-[140px] block" title={alert.resourceName}>
                        {alert.resourceName || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[12px] text-[rgba(0,0,0,0.45)]">{formatTime(alert.startDateTime)}</span>
                    </td>
                    <td className="px-4 py-3">
                      <SreInvestigateButton
                        resourceType="AZURE_VM"
                        resourceId={alert.alertId || alert.resourceName || ''}
                        resourceName={alert.resourceName}
                      />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default AzureAlertsTable;
