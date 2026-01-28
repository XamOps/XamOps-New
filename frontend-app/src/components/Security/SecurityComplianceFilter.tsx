/**
 * Security Compliance Filter Component
 *
 * Dropdown filter for compliance frameworks
 */

import React from 'react';

interface SecurityComplianceFilterProps {
  selectedCompliance: string;
  complianceFrameworks: string[];
  onComplianceChange: (compliance: string) => void;
}

const SecurityComplianceFilter: React.FC<SecurityComplianceFilterProps> = ({
  selectedCompliance,
  complianceFrameworks,
  onComplianceChange,
}) => {
  return (
    <div className="security-filter-bar">
      <div className="security-filter-content">
        <i className="fas fa-filter security-filter-icon"></i>
        <label htmlFor="compliance-filter" className="security-filter-label">
          Filter by Compliance:
        </label>
        <select
          id="compliance-filter"
          value={selectedCompliance}
          onChange={(e) => onComplianceChange(e.target.value)}
          className="security-filter-select"
        >
          <option value="All">All Compliance Frameworks</option>
          {complianceFrameworks.map((framework) => (
            <option key={framework} value={framework}>
              {framework}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
};

export default SecurityComplianceFilter;
