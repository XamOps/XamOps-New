import React from 'react';
import { Icon } from '@iconify/react';

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
    <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm px-5 py-3">
      <div className="flex items-center gap-3">
        <Icon icon="mdi:filter-outline" width={16} color="#6366f1" />
        <label htmlFor="compliance-filter" className="text-sm font-semibold text-[rgba(0,0,0,0.7)]">
          Filter by Compliance:
        </label>
        <select
          id="compliance-filter"
          value={selectedCompliance}
          onChange={(e) => onComplianceChange(e.target.value)}
          className="flex-1 max-w-xs px-3 py-1.5 text-sm bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-lg focus:outline-none focus:border-[rgba(0,0,0,0.2)]"
        >
          <option value="All">All Compliance Frameworks</option>
          {complianceFrameworks.map((framework) => (
            <option key={framework} value={framework}>{framework}</option>
          ))}
        </select>
      </div>
    </div>
  );
};

export default SecurityComplianceFilter;
