/**
 * AlertsGrafanaTable Component
 *
 * Table for displaying Grafana alerts
 */

import React from 'react';
import type { GrafanaAlert } from '../../types/alerts';

interface AlertsGrafanaTableProps {
  alerts: GrafanaAlert[];
  isLoading: boolean;
}

const AlertsGrafanaTable: React.FC<AlertsGrafanaTableProps> = ({
  alerts,
  isLoading,
}) => {
  if (isLoading) {
    return (
      <div className="alerts-loading-state">
        <p className="alerts-loading-text">Loading Grafana alerts...</p>
        <div className="alerts-loading-spinner"></div>
      </div>
    );
  }

  if (alerts.length === 0) {
    return (
      <div className="alerts-empty-state alerts-empty-grafana">
        <div className="alerts-empty-icon alerts-empty-icon-grafana">
          <svg className="alerts-grafana-logo" viewBox="0 0 16 16" fill="currentColor">
            <path d="M7.996 15.996a8 8 0 0 0 7.932-6.68H8v6.68ZM8 0v6.68h7.932a8 8 0 1 0-7.932 9.316V0Zm-.68 6.68H.064a8 8 0 0 0 7.256 9.316V6.68Z" />
          </svg>
        </div>
        <h3 className="alerts-empty-title">No Grafana Alerts Yet</h3>
        <p className="alerts-empty-message">
          Go to your Grafana contact point settings and click the "Test" button to send a sample alert.
        </p>
      </div>
    );
  }

  return (
    <div className="alerts-table-container">
      <table className="alerts-table alerts-grafana-table">
        <thead>
          <tr>
            <th className="alerts-table-th-name">Name (from label)</th>
            <th className="alerts-table-th-status">Status</th>
            <th className="alerts-table-th-description">Description</th>
            <th className="alerts-table-th-region">Region (from label)</th>
          </tr>
        </thead>
        <tbody>
          {alerts.map((alert) => (
            <tr
              key={`${alert.id}-${alert.name}`}
              className={`alerts-table-row alerts-row-${alert.status === 'firing' ? 'critical' : 'normal'}`}
            >
              <td className="alerts-cell-indicator">
                <span className="alerts-grafana-name">{alert.name}</span>
              </td>
              <td>
                <span
                  className={`alerts-status-badge ${
                    alert.status === 'firing'
                      ? 'alerts-status-firing'
                      : 'alerts-status-resolved'
                  }`}
                >
                  {alert.status}
                </span>
              </td>
              <td>
                <p className="alerts-grafana-description">
                  {alert.description || 'N/A'}
                </p>
              </td>
              <td>
                <span className="alerts-grafana-region">
                  {alert.region || 'N/A'}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
};

export default AlertsGrafanaTable;
