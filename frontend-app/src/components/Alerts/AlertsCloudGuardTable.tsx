/**
 * AlertsCloudGuardTable Component
 *
 * Table for displaying CloudGuard alerts (Quotas and Anomalies)
 */

import React from 'react';
import { Icon } from '@iconify/react';
import type { Alert, AlertSeverityClass } from '../../types/alerts';

interface AlertsCloudGuardTableProps {
  alerts: Alert[];
  isLoading: boolean;
  getUsagePercentage: (alert: Alert) => number;
  getUsageColor: (alert: Alert) => string;
  getUsageBadgeClass: (alert: Alert) => string;
  getAlertClass: (alert: Alert) => AlertSeverityClass;
  getServiceIconClass: (serviceName: string) => string;
  getAwsConsoleLink: (alert: Alert) => string;
}

const AlertsCloudGuardTable: React.FC<AlertsCloudGuardTableProps> = ({
  alerts,
  isLoading,
  getUsagePercentage,
  getUsageColor,
  getUsageBadgeClass,
  getAlertClass: _getAlertClass,
  getServiceIconClass,
  getAwsConsoleLink,
}) => {
  if (isLoading) {
    return (
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
              {['Service', 'Alert Type', 'Alert', 'Usage Progress', 'Action'].map((col) => (
                <th key={col} className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">
                  <div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded h-3 w-20" />
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: 8 }).map((_, i) => (
              <tr key={i} className="border-b border-[rgba(0,0,0,0.04)]">
                <td className="px-4 py-3"><div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded h-4 w-28" /></td>
                <td className="px-4 py-3"><div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded h-4 w-16" /></td>
                <td className="px-4 py-3"><div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded h-4 w-48" /></td>
                <td className="px-4 py-3"><div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded h-4 w-full" /></td>
                <td className="px-4 py-3"><div className="animate-pulse bg-[rgba(0,0,0,0.06)] rounded h-7 w-28" /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full">
        <thead>
          <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
            <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Service</th>
            <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Alert Type</th>
            <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Alert</th>
            <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Usage Progress</th>
            <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Action</th>
          </tr>
        </thead>
        <tbody>
          {alerts.map((alert) => (
            <tr
              key={alert.id}
              className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors"
            >
              {/* Service */}
              <td className="px-4 py-3">
                <div className="flex items-center gap-2.5">
                  <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${getServiceIconClass(alert.service)}`}>
                    <Icon icon="mdi:cog-outline" width={15} />
                  </div>
                  <span className="text-[13px] font-medium text-[rgba(0,0,0,0.88)]">{alert.service}</span>
                </div>
              </td>

              {/* Alert Type Badge */}
              <td className="px-4 py-3">
                <span
                  className={`inline-flex items-center px-2 py-0.5 rounded-md text-[11px] font-semibold ${alert.type === 'QUOTA'
                    ? 'bg-blue-100 text-blue-700'
                    : 'bg-amber-100 text-amber-700'
                    }`}
                >
                  {alert.type}
                </span>
              </td>
              <td>
                <div className="alerts-alert-info">
                  <p className="alerts-alert-name">{alert.name}</p>
                  {alert.type === 'QUOTA' ? (
                    <div className="flex items-center gap-2 mt-1 flex-wrap">
                      <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded text-xs font-medium bg-blue-50 text-blue-700 border border-blue-200">
                        <i className="fas fa-map-marker-alt"></i>
                        {alert.region || 'Unknown Region'}
                      </span>
                      <span className="alerts-alert-description">Limit: {alert.limit.toLocaleString()}</span>
                    </div>
                  ) : (
                    <p className="alerts-alert-description">{alert.description}</p>
                  )}
                </div>
              </td>

              {/* Usage Progress */}
              <td className="px-4 py-3 min-w-[160px]">
                {alert.type === 'QUOTA' ? (
                  <div className="space-y-1.5">
                    <div className="flex items-center justify-between gap-2">
                      <span className={`text-[11px] font-semibold px-1.5 py-0.5 rounded-md ${getUsageBadgeClass(alert)}`}>
                        {getUsagePercentage(alert).toFixed(1)}%
                      </span>
                      <span className="text-[11px] text-[rgba(0,0,0,0.4)]">
                        {alert.usage.toLocaleString()} / {alert.limit.toLocaleString()}
                      </span>
                    </div>
                    <div className="w-full h-1.5 bg-[rgba(0,0,0,0.06)] rounded-full overflow-hidden">
                      <div
                        className={`h-full rounded-full ${getUsageColor(alert)}`}
                        style={{ width: `${Math.min(getUsagePercentage(alert), 100)}%` }}
                      />
                    </div>
                  </div>
                ) : (
                  <p className="text-[13px] font-semibold text-amber-600">
                    ${alert.usage.toFixed(2)}
                  </p>
                )}
              </td>

              {/* Action */}
              <td className="px-4 py-3">
                <a
                  href={getAwsConsoleLink(alert)}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-xs font-medium rounded-md transition-colors"
                >
                  <Icon icon="mdi:open-in-new" width={12} />
                  <span>{alert.type === 'QUOTA' ? 'Request Increase' : 'Investigate'}</span>
                </a>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default AlertsCloudGuardTable;
