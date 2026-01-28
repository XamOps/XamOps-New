/**
 * AlertsEmptyState Component
 *
 * Empty state for when no alerts are found
 */

import React from 'react';

interface AlertsEmptyStateProps {
  onRefresh: () => void;
}

const AlertsEmptyState: React.FC<AlertsEmptyStateProps> = ({ onRefresh }) => {
  return (
    <div className="alerts-empty-state">
      <div className="alerts-empty-icon">
        <i className="fas fa-check-circle"></i>
      </div>
      <h3 className="alerts-empty-title">All Clear!</h3>
      <p className="alerts-empty-message">
        No critical alerts found for the selected filter.
      </p>
      <button onClick={onRefresh} className="alerts-btn alerts-btn-primary">
        <i className="fas fa-sync-alt"></i>
        <span>Refresh Check</span>
      </button>
    </div>
  );
};

export default AlertsEmptyState;
