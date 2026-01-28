/**
 * Security Findings Section Component
 *
 * Collapsible sections for security findings grouped by severity
 */

import React from 'react';
import type { SecurityFinding, SeverityLevel } from '../../types/security';

interface SecurityFindingsSectionProps {
  severity: SeverityLevel;
  findings: SecurityFinding[];
  isOpen: boolean;
  onToggle: () => void;
}

/**
 * Get header color classes based on severity
 */
const getSeverityHeaderColor = (severity: SeverityLevel): string => {
  const colors: Record<SeverityLevel, string> = {
    Critical: 'security-severity-header-critical',
    High: 'security-severity-header-high',
    Medium: 'security-severity-header-medium',
    Low: 'security-severity-header-low',
  };
  return colors[severity] || 'security-severity-header-default';
};

/**
 * Get badge color classes based on severity
 */
const getSeverityBadgeColor = (severity: SeverityLevel): string => {
  const colors: Record<SeverityLevel, string> = {
    Critical: 'security-badge-critical',
    High: 'security-badge-high',
    Medium: 'security-badge-medium',
    Low: 'security-badge-low',
  };
  return colors[severity] || 'security-badge-default';
};

/**
 * Get icon for severity level
 */
const getSeverityIcon = (severity: SeverityLevel): string => {
  const icons: Record<SeverityLevel, string> = {
    Critical: 'fa-skull-crossbones',
    High: 'fa-exclamation-triangle',
    Medium: 'fa-exclamation-circle',
    Low: 'fa-info-circle',
  };
  return icons[severity] || 'fa-question-circle';
};

/**
 * Get icon background color based on severity
 */
const getSeverityIconBg = (severity: SeverityLevel): string => {
  const colors: Record<SeverityLevel, string> = {
    Critical: 'security-icon-bg-critical',
    High: 'security-icon-bg-high',
    Medium: 'security-icon-bg-medium',
    Low: 'security-icon-bg-low',
  };
  return colors[severity] || 'security-icon-bg-default';
};

const SecurityFindingsSection: React.FC<SecurityFindingsSectionProps> = ({
  severity,
  findings,
  isOpen,
  onToggle,
}) => {
  if (findings.length === 0) {
    return null;
  }

  return (
    <div className="security-findings-section security-card-fade">
      <div className="security-section-wrapper">
        <button
          onClick={onToggle}
          className={`security-section-header ${getSeverityHeaderColor(severity)}`}
        >
          <div className="security-section-header-left">
            <div className={`security-section-icon ${getSeverityIconBg(severity)}`}>
              <i className={`fas ${getSeverityIcon(severity)}`}></i>
            </div>
            <h3 className="security-section-title">{severity} Severity Findings</h3>
          </div>
          <div className="security-section-header-right">
            <span className={`security-severity-badge ${getSeverityBadgeColor(severity)}`}>
              {findings.length}
            </span>
            <i
              className={`fas fa-chevron-down security-chevron ${isOpen ? 'security-chevron-open' : ''}`}
            ></i>
          </div>
        </button>

        {isOpen && (
          <div className="security-section-content">
            <div className="security-table-container">
              <table className="security-table">
                <thead>
                  <tr>
                    <th className="security-table-th-category">Category</th>
                    <th className="security-table-th-description">Description</th>
                    <th className="security-table-th-resource">Resource</th>
                    <th className="security-table-th-source">Source & Control</th>
                  </tr>
                </thead>
                <tbody>
                  {findings.map((finding, index) => (
                    <tr key={`${finding.resourceId}-${index}`} className="security-table-row">
                      <td>
                        <div className="security-category-display">{finding.category}</div>
                      </td>
                      <td>
                        <div className="security-description-text">{finding.description}</div>
                      </td>
                      <td>
                        <div className="security-resource-info">
                          <div className="security-resource-id">{finding.resourceId}</div>
                          <div className="security-resource-region">{finding.region}</div>
                        </div>
                      </td>
                      <td>
                        <span
                          className={`security-compliance-badge ${
                            finding.complianceFramework?.includes('SOC2')
                              ? 'security-soc2-badge'
                              : finding.complianceFramework?.includes('Prowler')
                              ? 'security-prowler-badge'
                              : ''
                          }`}
                        >
                          {(finding.complianceFramework || 'Unknown') +
                            (finding.controlId ? ` (${finding.controlId})` : '')}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default SecurityFindingsSection;
