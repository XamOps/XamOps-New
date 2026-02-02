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
    <div className="flex flex-wrap items-center gap-4">
      {filters.map((filter) => (
        <button
          key={filter.value}
          onClick={() => onFilterChange(filter.value)}
          className={`filter-button px-4 py-2 text-sm font-semibold rounded-lg transition-all ${
            currentSeverity === filter.value
              ? 'active'
              : 'text-gray-700 bg-white border border-gray-300 hover:bg-gray-100'
          }`}
        >
          {filter.label}
        </button>
      ))}
    </div>
  );
};

export default FilterButtons;
