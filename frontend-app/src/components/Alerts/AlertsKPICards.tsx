/**
 * AlertsKPICards Component
 *
 * KPI cards showing alert counts by severity
 */

import React from 'react';
import type { AlertCounts } from '../../types/alerts';

interface AlertsKPICardsProps {
  alertCounts: AlertCounts;
  isLoading: boolean;
}

const AlertsKPICards: React.FC<AlertsKPICardsProps> = ({
  alertCounts,
  isLoading,
}) => {
  if (isLoading) {
    return (
      <div className="alerts-kpi-grid">
        <div className="alerts-skeleton alerts-skeleton-card"></div>
        <div className="alerts-skeleton alerts-skeleton-card"></div>
        <div className="alerts-skeleton alerts-skeleton-card"></div>
        <div className="alerts-skeleton alerts-skeleton-card"></div>
      </div>
    );
  }

  return (
    <div className="alerts-kpi-grid">
      {/* Total Alerts */}
      <div className="alerts-card alerts-card-fade">
        <div className="alerts-card-content">
          <div className="alerts-card-info">
            <p className="alerts-card-label">Total Alerts</p>
            <p className="alerts-card-value alerts-gradient-text">
              {alertCounts.total}
            </p>
            <p className="alerts-card-sublabel">monitored services</p>
          </div>
          <div className="alerts-card-icon alerts-icon-blue">
            <i className="fas fa-list"></i>
          </div>
        </div>
      </div>

      {/* Critical Usage */}
      <div className="alerts-card alerts-card-fade">
        <div className="alerts-card-content">
          <div className="alerts-card-info">
            <p className="alerts-card-label">Critical Usage</p>
            <p className="alerts-card-value alerts-text-critical">
              {alertCounts.critical}
            </p>
            <p className="alerts-card-sublabel">above 90%</p>
          </div>
          <div className="alerts-card-icon alerts-icon-critical">
            <i className="fas fa-exclamation-triangle"></i>
          </div>
        </div>
      </div>

      {/* Warning */}
      <div className="alerts-card alerts-card-fade">
        <div className="alerts-card-content">
          <div className="alerts-card-info">
            <p className="alerts-card-label">Warning</p>
            <p className="alerts-card-value alerts-text-warning">
              {alertCounts.warning}
            </p>
            <p className="alerts-card-sublabel">75-90%</p>
          </div>
          <div className="alerts-card-icon alerts-icon-warning">
            <i className="fas fa-exclamation-circle"></i>
          </div>
        </div>
      </div>

      {/* Healthy */}
      <div className="alerts-card alerts-card-fade">
        <div className="alerts-card-content">
          <div className="alerts-card-info">
            <p className="alerts-card-label">Healthy</p>
            <p className="alerts-card-value alerts-text-healthy">
              {alertCounts.healthy}
            </p>
            <p className="alerts-card-sublabel">below 75%</p>
          </div>
          <div className="alerts-card-icon alerts-icon-healthy">
            <i className="fas fa-check-circle"></i>
          </div>
        </div>
      </div>
    </div>
  );
};

export default AlertsKPICards;
