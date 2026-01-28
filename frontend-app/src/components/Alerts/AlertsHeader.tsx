/**
 * AlertsHeader Component
 *
 * Header section for the Alerts page with title and refresh button
 */

import React from 'react';

interface AlertsHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AlertsHeader: React.FC<AlertsHeaderProps> = ({
  isRefreshing,
  onRefresh,
}) => {
  return (
    <header className="alerts-header">
      <div className="alerts-header-left">
        <div className="alerts-header-icon">
          <i className="fas fa-bell"></i>
        </div>
        <div>
          <h1 className="alerts-header-title">Alerts</h1>
          <p className="alerts-header-subtitle">
            Monitor critical alerts that require immediate attention
          </p>
        </div>
      </div>
      <div className="alerts-header-right">
        <div className="alerts-monitoring-indicator">
          <div className="alerts-pulse-dot"></div>
          <span>Active monitoring</span>
        </div>
        <button
          onClick={onRefresh}
          className="alerts-btn alerts-btn-primary"
          disabled={isRefreshing}
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Scanning...' : 'Refresh Alerts'}</span>
        </button>
      </div>
    </header>
  );
};

export default AlertsHeader;
