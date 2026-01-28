/**
 * AlertsTabs Component
 *
 * Tab navigation for switching between CloudGuard and Grafana alerts
 */

import React from 'react';
import type { AlertTab } from '../../types/alerts';

interface AlertsTabsProps {
  activeTab: AlertTab;
  onTabChange: (tab: AlertTab) => void;
}

const AlertsTabs: React.FC<AlertsTabsProps> = ({ activeTab, onTabChange }) => {
  return (
    <div className="alerts-tabs-container">
      <nav className="alerts-tabs" aria-label="Tabs">
        <button
          onClick={() => onTabChange('cloudguard')}
          className={`alerts-tab ${activeTab === 'cloudguard' ? 'alerts-tab-active' : ''}`}
        >
          <i className="fas fa-shield-alt alerts-tab-icon"></i>
          <span>CloudGuard Alerts</span>
        </button>
        <button
          onClick={() => onTabChange('grafana')}
          className={`alerts-tab ${activeTab === 'grafana' ? 'alerts-tab-active' : ''}`}
        >
          <i className="fas fa-chart-line alerts-tab-icon"></i>
          <span>Grafana Alerts</span>
        </button>
      </nav>
    </div>
  );
};

export default AlertsTabs;
