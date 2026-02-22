import React, { useState } from 'react';
import type { AzureMonitorAlert } from '../../types/azureAlerts';

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
  Verbose:       'bg-gray-100 text-gray-600 border-gray-200',
};

const STATE_BADGE: Record<string, string> = {
  New:          'bg-red-100 text-red-700',
  Acknowledged: 'bg-blue-100 text-blue-700',
  Closed:       'bg-green-100 text-green-700',
};

const SkeletonRow = () => (
  <tr className="border-b border-gray-100 animate-pulse">
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-36"></div></td>
    <td className="px-4 py-4"><div className="h-5 bg-gray-200 rounded w-20"></div></td>
    <td className="px-4 py-4"><div className="h-5 bg-gray-200 rounded w-24"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-16"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-28"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-24"></div></td>
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
      <div className="glass-card rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-100">
          <div className="h-4 bg-gray-200 rounded w-32 animate-pulse"></div>
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
    <div className="glass-card rounded-xl shadow-sm overflow-hidden">
      {/* Header + filter tabs */}
      <div className="px-5 py-3 border-b border-gray-100 flex items-center justify-between flex-wrap gap-3">
        <span className="text-sm font-bold text-gray-700">Monitor Alerts</span>
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
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                }`}
              >
                {tab}
                {count > 0 && (
                  <span className={`ml-1.5 text-xs px-1 py-0.5 rounded-full ${
                    isActive ? 'bg-white/30 text-white' : 'bg-gray-200 text-gray-600'
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
        <div className="p-12 text-center">
          <div className="w-14 h-14 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-3">
            <i className="fas fa-check-circle text-green-500 text-2xl"></i>
          </div>
          <h3 className="text-base font-bold text-gray-700 mb-1">
            {activeFilter === 'All' ? 'No Alerts Detected' : `No ${activeFilter} Alerts`}
          </h3>
          <p className="text-sm text-gray-500">
            {activeFilter === 'All'
              ? 'Azure Monitor found no alerts for this subscription.'
              : `No alerts in ${activeFilter.toLowerCase()} state.`}
          </p>
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50/60">
              <tr>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Alert Name</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Severity</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">State</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Signal</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Resource</th>
                <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Fired</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((alert, idx) => {
                const severityClass = SEVERITY_BADGE[alert.severityLabel] ?? SEVERITY_BADGE.Informational;
                const stateClass = STATE_BADGE[alert.alertState] ?? 'bg-gray-100 text-gray-600';
                return (
                  <tr key={alert.alertId || idx} className="border-t border-gray-100 hover:bg-white/30 transition-colors">
                    <td className="px-4 py-3">
                      <div>
                        <span className="text-gray-800 font-medium text-xs line-clamp-1 max-w-[200px] block" title={alert.alertName}>
                          {alert.alertName || '—'}
                        </span>
                        {alert.description && (
                          <span className="text-gray-400 text-xs line-clamp-1 max-w-[200px] block" title={alert.description}>
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
                      <span className="text-xs text-gray-500 bg-gray-100 px-2 py-0.5 rounded">
                        {alert.signalType || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-gray-600 text-xs truncate max-w-[140px] block" title={alert.resourceName}>
                        {alert.resourceName || '—'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-gray-500 text-xs">{formatTime(alert.startDateTime)}</span>
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
