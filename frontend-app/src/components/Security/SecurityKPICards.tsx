/**
 * Security KPI Cards Component
 *
 * Displays summary cards for security findings by severity
 */

import React from 'react';
import type { SeverityCounts } from '../../types/security';

interface SecurityKPICardsProps {
  totalFindings: number;
  severityCounts: SeverityCounts;
  isLoading: boolean;
}

const SecurityKPICards: React.FC<SecurityKPICardsProps> = ({
  totalFindings,
  severityCounts,
  isLoading,
}) => {
  if (isLoading) {
    return (
      <div className="security-kpi-grid">
        {[...Array(4)].map((_, i) => (
          <div key={i} className="security-skeleton security-skeleton-card"></div>
        ))}
      </div>
    );
  }

  return (
    <div className="security-kpi-grid">
      {/* Total Findings */}
      <div className="security-card security-card-fade">
        <div className="security-card-content">
          <div className="security-card-info">
            <h3 className="security-card-label">Total Findings</h3>
            <p className="security-card-value security-gradient-text">{totalFindings}</p>
            <p className="security-card-sublabel">security issues detected</p>
          </div>
          <div className="security-card-icon security-icon-blue">
            <i className="fas fa-search"></i>
          </div>
        </div>
      </div>

      {/* Critical */}
      <div className="security-card security-card-fade">
        <div className="security-card-content">
          <div className="security-card-info">
            <h3 className="security-card-label">Critical</h3>
            <p className="security-card-value security-text-critical">{severityCounts.Critical || 0}</p>
            <p className="security-card-sublabel">immediate attention</p>
          </div>
          <div className="security-card-icon security-icon-critical">
            <i className="fas fa-exclamation-triangle"></i>
          </div>
        </div>
      </div>

      {/* High Priority */}
      <div className="security-card security-card-fade">
        <div className="security-card-content">
          <div className="security-card-info">
            <h3 className="security-card-label">High Priority</h3>
            <p className="security-card-value security-text-high">{severityCounts.High || 0}</p>
            <p className="security-card-sublabel">requires action</p>
          </div>
          <div className="security-card-icon security-icon-high">
            <i className="fas fa-fire"></i>
          </div>
        </div>
      </div>

      {/* Medium & Low */}
      <div className="security-card security-card-fade">
        <div className="security-card-content">
          <div className="security-card-info">
            <h3 className="security-card-label">Medium & Low</h3>
            <p className="security-card-value security-text-medium">
              {(severityCounts.Medium || 0) + (severityCounts.Low || 0)}
            </p>
            <p className="security-card-sublabel">monitor closely</p>
          </div>
          <div className="security-card-icon security-icon-medium">
            <i className="fas fa-eye"></i>
          </div>
        </div>
      </div>
    </div>
  );
};

export default SecurityKPICards;
