/**
 * AlertsGrafanaTable Component
 *
 * Table for displaying Grafana alerts
 */

import React from 'react';
import { Icon } from '@iconify/react';
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
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
            <Icon icon="mdi:chart-line" width={16} className="text-indigo-600" />
          </div>
          <div>
            <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Grafana Alerts</h2>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">External alert monitoring via Grafana</p>
          </div>
        </div>
        <div className="flex items-center justify-center py-16 gap-3">
          <Icon icon="mdi:loading" width={20} className="animate-spin text-indigo-500" />
          <span className="text-[13px] text-[rgba(0,0,0,0.45)]">Loading Grafana alerts...</span>
        </div>
      </div>
    );
  }

  if (alerts.length === 0) {
    return (
      <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
        <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
          <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
            <Icon icon="mdi:chart-line" width={16} className="text-indigo-600" />
          </div>
          <div>
            <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Grafana Alerts</h2>
            <p className="text-[11px] text-[rgba(0,0,0,0.4)]">External alert monitoring via Grafana</p>
          </div>
        </div>
        <div className="py-16 text-center">
          <div className="w-14 h-14 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mx-auto mb-3">
            <Icon icon="mdi:chart-line" width={28} className="text-emerald-500" />
          </div>
          <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Grafana Alerts Yet</h3>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
            Go to your Grafana contact point settings and click the "Test" button to send a sample alert.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
      <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
        <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
          <Icon icon="mdi:chart-line" width={16} className="text-indigo-600" />
        </div>
        <div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Grafana Alerts</h2>
          <p className="text-[11px] text-[rgba(0,0,0,0.4)]">External alert monitoring via Grafana</p>
        </div>
      </div>
      <div className="overflow-x-auto">
        <table className="w-full">
          <thead>
            <tr className="bg-[rgba(0,0,0,0.02)] border-b border-[rgba(0,0,0,0.06)]">
              <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Name (from label)</th>
              <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Status</th>
              <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Description</th>
              <th className="px-4 py-3 text-left text-[12px] font-semibold uppercase tracking-[0.09em] text-[rgba(0,0,0,0.6)]">Region (from label)</th>
            </tr>
          </thead>
          <tbody>
            {alerts.map((alert) => (
              <tr
                key={`${alert.id}-${alert.name}`}
                className="border-b border-[rgba(0,0,0,0.04)] hover:bg-[rgba(0,0,0,0.025)] transition-colors"
              >
                <td className="px-4 py-3">
                  <span className="text-[13px] font-medium text-[rgba(0,0,0,0.88)]">{alert.name}</span>
                </td>
                <td className="px-4 py-3">
                  <span
                    className={`inline-flex items-center px-2 py-0.5 rounded-md text-[11px] font-semibold ${
                      alert.status === 'firing'
                        ? 'bg-red-100 text-red-700'
                        : 'bg-emerald-100 text-emerald-700'
                    }`}
                  >
                    {alert.status}
                  </span>
                </td>
                <td className="px-4 py-3">
                  <p className="text-[13px] text-[rgba(0,0,0,0.6)]">{alert.description || 'N/A'}</p>
                </td>
                <td className="px-4 py-3">
                  <span className="text-[13px] text-[rgba(0,0,0,0.6)]">{alert.region || 'N/A'}</span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
};

export default AlertsGrafanaTable;
