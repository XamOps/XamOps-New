/**
 * GcpRightsizingHeader Component
 *
 * Header section for GCP Rightsizing page with title and refresh button
 */

import React from 'react';

interface GcpRightsizingHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

export const GcpRightsizingHeader: React.FC<GcpRightsizingHeaderProps> = ({
  isRefreshing,
  onRefresh,
}) => {
  return (
    <header className="gcp-rightsizing-header">
      <div className="gcp-rightsizing-header-content">
        <div className="gcp-rightsizing-header-icon">
          <i className="fas fa-compress-arrows-alt"></i>
        </div>
        <div className="gcp-rightsizing-header-text">
          <h1>GCP Rightsizing</h1>
          <p>Optimize your GCP resource sizing</p>
        </div>
      </div>
      <div className="gcp-rightsizing-header-actions">
        <button
          onClick={onRefresh}
          className={`gcp-rightsizing-refresh-btn ${isRefreshing ? 'refreshing' : ''}`}
          disabled={isRefreshing}
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Analyzing...' : 'Refresh Analysis'}</span>
        </button>
      </div>
    </header>
  );
};

export default GcpRightsizingHeader;
