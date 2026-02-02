/**
 * GcpCostHeader Component
 * Header with GCP branding, title, and refresh button
 */

import React from 'react';

interface GcpCostHeaderProps {
  isLoading: boolean;
  onRefresh: () => void;
}

const GcpCostHeader: React.FC<GcpCostHeaderProps> = ({ isLoading, onRefresh }) => {
  return (
    <header className="gcp-cost-header">
      <div className="gcp-cost-header-left">
        <div className="gcp-cost-header-icon">
          <i className="fas fa-dollar-sign"></i>
        </div>
        <div className="gcp-cost-header-text">
          <h1>GCP Cost Management</h1>
          <p>Analyze and optimize your GCP spending</p>
        </div>
      </div>
      <div className="gcp-cost-header-right">
        <span className="gcp-cost-live-badge">
          <span className="gcp-cost-live-dot"></span>
          Live data
        </span>
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="gcp-cost-refresh-btn"
        >
          <i className={`fas fa-sync-alt ${isLoading ? 'fa-spin' : ''}`}></i>
          <span>{isLoading ? 'Refreshing...' : 'Refresh Data'}</span>
        </button>
      </div>
    </header>
  );
};

export default GcpCostHeader;
