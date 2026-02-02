/**
 * GcpWasteHeader Component
 * Header with GCP branding, title, and scan button
 */

import React from 'react';

interface GcpWasteHeaderProps {
  isRefreshing: boolean;
  onRefresh: () => void;
}

const GcpWasteHeader: React.FC<GcpWasteHeaderProps> = ({ isRefreshing, onRefresh }) => {
  return (
    <header className="gcp-waste-header">
      <div className="gcp-waste-header-left">
        <div className="gcp-waste-header-icon">
          <i className="fas fa-recycle"></i>
        </div>
        <div className="gcp-waste-header-text">
          <h1>GCP Waste Management</h1>
          <p>Identify and eliminate unused cloud resources</p>
        </div>
      </div>
      <button
        onClick={onRefresh}
        disabled={isRefreshing}
        className="gcp-waste-scan-btn"
      >
        <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
        <span>{isRefreshing ? 'Scanning...' : 'Scan Resources'}</span>
      </button>
    </header>
  );
};

export default GcpWasteHeader;
