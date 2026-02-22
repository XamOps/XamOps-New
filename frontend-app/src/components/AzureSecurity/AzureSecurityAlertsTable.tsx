import React from 'react';
import type { AzureSecurityAlert } from '../../types/azureSecurity';

interface AzureSecurityAlertsTableProps {
  alerts: AzureSecurityAlert[];
  isLoading: boolean;
}

const SEVERITY_BADGE: Record<string, string> = {
  High: 'bg-red-100 text-red-700 border-red-200',
  Medium: 'bg-amber-100 text-amber-700 border-amber-200',
  Low: 'bg-blue-100 text-blue-700 border-blue-200',
  Informational: 'bg-gray-100 text-gray-600 border-gray-200',
};

const STATUS_BADGE: Record<string, string> = {
  Active: 'bg-red-100 text-red-700',
  Dismissed: 'bg-gray-100 text-gray-500',
  Resolved: 'bg-green-100 text-green-700',
};

const SkeletonRow = () => (
  <tr className="border-b border-gray-100 animate-pulse">
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-40"></div></td>
    <td className="px-4 py-4"><div className="h-5 bg-gray-200 rounded w-14"></div></td>
    <td className="px-4 py-4"><div className="h-5 bg-gray-200 rounded w-16"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-28"></div></td>
    <td className="px-4 py-4"><div className="h-3 bg-gray-200 rounded w-20"></div></td>
  </tr>
);

function formatTime(ts: string): string {
  if (!ts) return '—';
  try {
    return new Date(ts).toLocaleDateString('en-IN', {
      day: '2-digit', month: 'short', year: 'numeric',
    });
  } catch {
    return ts;
  }
}

const AzureSecurityAlertsTable: React.FC<AzureSecurityAlertsTableProps> = ({ alerts, isLoading }) => {
  if (isLoading) {
    return (
      <div className="glass-card rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-3 border-b border-gray-100">
          <div className="h-4 bg-gray-200 rounded w-28 animate-pulse"></div>
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

  if (alerts.length === 0) {
    return (
      <div className="glass-card rounded-xl p-12 text-center shadow-sm">
        <div className="w-14 h-14 bg-green-100 rounded-full flex items-center justify-center mx-auto mb-3">
          <i className="fas fa-shield-alt text-green-500 text-2xl"></i>
        </div>
        <h3 className="text-base font-bold text-gray-700 mb-1">No Active Alerts</h3>
        <p className="text-sm text-gray-500">No security alerts detected for this subscription.</p>
      </div>
    );
  }

  return (
    <div className="glass-card rounded-xl shadow-sm overflow-hidden">
      <div className="px-5 py-3 border-b border-gray-100 flex items-center justify-between">
        <span className="text-sm font-bold text-gray-700">Security Alerts</span>
        <span className="text-xs text-gray-500">{alerts.length} total</span>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full text-sm">
          <thead className="bg-gray-50/60">
            <tr>
              <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Alert</th>
              <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Severity</th>
              <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Status</th>
              <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Resource</th>
              <th className="px-4 py-2 text-left text-xs font-bold uppercase tracking-wider text-gray-500">Time</th>
            </tr>
          </thead>
          <tbody>
            {alerts.map((alert, idx) => {
              const severityClass = SEVERITY_BADGE[alert.severity] ?? SEVERITY_BADGE.Informational;
              const statusClass = STATUS_BADGE[alert.status] ?? 'bg-gray-100 text-gray-600';
              return (
                <tr key={alert.alertId || idx} className="border-t border-gray-100 hover:bg-white/30 transition-colors">
                  <td className="px-4 py-3">
                    <span className="text-gray-800 font-medium text-xs line-clamp-2 max-w-[220px]" title={alert.alertName}>
                      {alert.alertName || '—'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold border ${severityClass}`}>
                      {alert.severity || '—'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-xs font-semibold ${statusClass}`}>
                      {alert.status || '—'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className="text-gray-600 text-xs truncate max-w-[140px] block" title={alert.resourceName}>
                      {alert.resourceName || '—'}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    <span className="text-gray-500 text-xs">{formatTime(alert.timeGenerated)}</span>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default AzureSecurityAlertsTable;
