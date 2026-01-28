/**
 * AlertsCloudGuardTable Component
 *
 * Table for displaying CloudGuard alerts (Quotas and Anomalies)
 */

import React from 'react';
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
  getAlertClass,
  getServiceIconClass,
  getAwsConsoleLink,
}) => {
  if (isLoading) {
    return (
      <div className="alerts-table-container">
        <table className="alerts-table">
          <thead>
            <tr>
              <th><div className="alerts-skeleton-inline"></div></th>
              <th><div className="alerts-skeleton-inline"></div></th>
              <th><div className="alerts-skeleton-inline"></div></th>
              <th><div className="alerts-skeleton-inline"></div></th>
              <th><div className="alerts-skeleton-inline"></div></th>
            </tr>
          </thead>
          <tbody>
            {Array.from({ length: 8 }).map((_, i) => (
              <tr key={i} className="alerts-table-row">
                <td><div className="alerts-skeleton-inline"></div></td>
                <td><div className="alerts-skeleton-inline"></div></td>
                <td><div className="alerts-skeleton-inline"></div></td>
                <td><div className="alerts-skeleton-inline alerts-skeleton-progress"></div></td>
                <td><div className="alerts-skeleton-inline"></div></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    );
  }

  return (
    <div className="alerts-table-container">
      <table className="alerts-table">
        <thead>
          <tr>
            <th className="alerts-table-th-service">Service</th>
            <th className="alerts-table-th-type">Alert Type</th>
            <th className="alerts-table-th-alert">Alert</th>
            <th className="alerts-table-th-progress">Usage Progress</th>
            <th className="alerts-table-th-action">Action</th>
          </tr>
        </thead>
        <tbody>
          {alerts.map((alert) => (
            <tr
              key={alert.id}
              className={`alerts-table-row alerts-row-${getAlertClass(alert)}`}
            >
              <td className="alerts-cell-indicator">
                <div className="alerts-service-cell">
                  <div className={`alerts-service-icon ${getServiceIconClass(alert.service)}`}>
                    <i className="fas fa-cog"></i>
                  </div>
                  <span className="alerts-service-name">{alert.service}</span>
                </div>
              </td>
              <td>
                <span className={`alerts-type-badge alerts-type-${alert.type.toLowerCase()}`}>
                  {alert.type}
                </span>
              </td>
              <td>
                <div className="alerts-alert-info">
                  <p className="alerts-alert-name">{alert.name}</p>
                  <p className="alerts-alert-description">
                    {alert.type === 'QUOTA'
                      ? `${alert.region} - Limit: ${alert.limit.toLocaleString()}`
                      : alert.description}
                  </p>
                </div>
              </td>
              <td>
                {alert.type === 'QUOTA' ? (
                  <div className="alerts-usage-container">
                    <div className="alerts-usage-header">
                      <span className={`alerts-usage-badge ${getUsageBadgeClass(alert)}`}>
                        {getUsagePercentage(alert).toFixed(1)}%
                      </span>
                      <span className="alerts-usage-text">
                        {alert.usage.toLocaleString()} / {alert.limit.toLocaleString()}
                      </span>
                    </div>
                    <div className="alerts-progress-bar">
                      <div
                        className={`alerts-progress-fill ${getUsageColor(alert)}`}
                        style={{ width: `${Math.min(getUsagePercentage(alert), 100)}%` }}
                      ></div>
                    </div>
                  </div>
                ) : (
                  <p className="alerts-anomaly-value">
                    ${alert.usage.toFixed(2)}
                  </p>
                )}
              </td>
              <td className="alerts-action-cell">
                <a
                  href={getAwsConsoleLink(alert)}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="alerts-btn alerts-btn-action"
                >
                  <i className="fas fa-external-link-alt"></i>
                  <span>
                    {alert.type === 'QUOTA' ? 'Request Increase' : 'Investigate'}
                  </span>
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
