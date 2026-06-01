/**
 * Filter Buttons Component
 * Filter insights by severity
 */

import React from 'react';
import { PerformanceSeverity } from '../../types/gcpPerformance';

interface FilterButtonsProps {
  currentSeverity: 'ALL' | PerformanceSeverity;
  onFilterChange: (severity: 'ALL' | PerformanceSeverity) => void;
  selectedCount: number;
}

const FilterButtons: React.FC<FilterButtonsProps> = ({
  currentSeverity,
  onFilterChange,
  selectedCount
}) => {
  if (selectedCount > 0) {
    return null; // Hide filters when insights are selected
  }

  const filters: Array<{ label: string; value: 'ALL' | PerformanceSeverity }> = [
    { label: 'All Insights', value: 'ALL' },
    { label: 'Critical', value: 'CRITICAL' },
    { label: 'Warning', value: 'WARNING' },
    { label: 'Weak Warning', value: 'WEAK_WARNING' },
  ];

  return (
    <div className="flex flex-wrap items-center gap-2">
      {filters.map((filter) => (
        <button
          key={filter.value}
          onClick={() => onFilterChange(filter.value)}
          className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all ${
            currentSeverity === filter.value
              ? 'bg-indigo-600 text-white shadow-sm'
              : 'border border-[rgba(0,0,0,0.12)] text-gray-600 hover:bg-gray-50'
          }`}
        >
          {filter.label}
        </button>
      ))}
    </div>
  );
};

export default FilterButtons;
