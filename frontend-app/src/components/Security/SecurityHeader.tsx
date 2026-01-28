/**
 * Security Header Component
 *
 * Page header with title, status indicators, and action buttons
 */

import React from 'react';
import type { ProwlerStatus } from '../../types/security';

interface SecurityHeaderProps {
  prowlerStatus: ProwlerStatus;
  isRefreshing: boolean;
  isExporting: boolean;
  onExport: () => void;
  onRefresh: () => void;
}

const SecurityHeader: React.FC<SecurityHeaderProps> = ({
  prowlerStatus,
  isRefreshing,
  isExporting,
  onExport,
  onRefresh,
}) => {
  return (
    <header className="security-header">
      <div className="security-header-left">
        <div className="security-header-icon">
          <i className="fas fa-shield-alt"></i>
        </div>
        <div>
          <h1 className="security-header-title">Security Center</h1>
          <p className="security-header-subtitle">Monitor security findings and compliance status</p>
        </div>
      </div>

      <div className="security-header-right">
        {prowlerStatus.status === 'RUNNING' && (
          <div className="security-scan-indicator">
            <div className="security-scan-dot"></div>
            <span>Deep Scan Running...</span>
          </div>
        )}

        <div className="security-monitoring-indicator">
          <div className="security-pulse-dot"></div>
          <span>Security monitoring</span>
        </div>

        <button
          onClick={onExport}
          disabled={isExporting}
          className="security-btn security-btn-success"
        >
          <i className={`fas ${isExporting ? 'fa-spinner fa-spin' : 'fa-file-excel'}`}></i>
          <span>{isExporting ? 'Exporting...' : 'Export Report'}</span>
        </button>

        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="security-btn security-btn-primary"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Scanning...' : 'Refresh Scan'}</span>
        </button>
      </div>
    </header>
  );
};

export default SecurityHeader;
