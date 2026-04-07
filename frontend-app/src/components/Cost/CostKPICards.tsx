/**
 * CostKPICards Component
 * Displays key performance indicators for cost management
 */

import React from 'react';
import type { CostDataItem, CostFilters } from '../../types/cost';

interface CostKPICardsProps {
  costData: CostDataItem[];
  filters: CostFilters;
  isLoading: boolean;
  labelFilterActive?: boolean;
}

const CostKPICards: React.FC<CostKPICardsProps> = ({ costData, filters, isLoading, labelFilterActive }) => {
  const totalCost = costData.reduce((sum, item) => sum + item.amount, 0);
  const sortedData = [...costData].sort((a, b) => b.amount - a.amount);
  const topSpender = sortedData.length > 0 ? sortedData[0] : null;

  return (
    <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
      {/* Total Cost Card */}
      <div className="bg-white/90 backdrop-blur border border-gray-200 rounded-2xl p-6 shadow-sm hover:shadow-xl hover:-translate-y-1 transition-all duration-300 border-t-4 border-t-blue-500">
        <div className="flex items-center justify-between mb-4">
          <div className="w-12 h-12 bg-gradient-to-br from-blue-500 to-blue-600 rounded-xl flex items-center justify-center shadow-md">
            <i className="fas fa-chart-line text-white text-xl"></i>
          </div>
          <div className="text-right">
            <p className="text-sm text-gray-600 font-medium">Total Cost</p>
            <p className="text-2xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-blue-600 to-purple-600">
              {isLoading ? '...' : `$${totalCost.toFixed(2)}`}
            </p>
          </div>
        </div>
        <div className="text-sm text-gray-500 flex items-center gap-2 flex-wrap">
          <i className="far fa-calendar-alt mr-1"></i>
          <span>Spending for {filters.startDate} to {filters.endDate}</span>
          {labelFilterActive && (
            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-semibold bg-indigo-100 text-indigo-700 border border-indigo-200">
              <i className="fas fa-tag text-indigo-500" style={{ fontSize: '9px' }} />
              Label filtered
            </span>
          )}
        </div>
      </div>

      {/* Categories Card */}
      <div className="bg-white/90 backdrop-blur border border-gray-200 rounded-2xl p-6 shadow-sm hover:shadow-xl hover:-translate-y-1 transition-all duration-300 border-t-4 border-t-purple-500">
        <div className="flex items-center justify-between mb-4">
          <div className="w-12 h-12 bg-gradient-to-br from-purple-500 to-purple-600 rounded-xl flex items-center justify-center shadow-md">
            <i className="fas fa-layer-group text-white text-xl"></i>
          </div>
          <div className="text-right">
            <p className="text-sm text-gray-600 font-medium">Categories</p>
            <p className="text-2xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-purple-600 to-pink-600">
              {isLoading ? '...' : costData.length}
            </p>
          </div>
        </div>
        <div className="text-sm text-gray-500 flex items-center">
          <i className="fas fa-filter mr-2"></i>
          <span>Grouped by {filters.groupBy.toLowerCase()}</span>
        </div>
      </div>

      {/* Top Spender Card */}
      <div className="bg-white/90 backdrop-blur border border-gray-200 rounded-2xl p-6 shadow-sm hover:shadow-xl hover:-translate-y-1 transition-all duration-300 border-t-4 border-t-amber-500">
        <div className="flex items-center justify-between mb-4">
          <div className="w-12 h-12 bg-gradient-to-br from-amber-500 to-amber-600 rounded-xl flex items-center justify-center shadow-md">
            <i className="fas fa-crown text-white text-xl"></i>
          </div>
          <div className="text-right">
            <p className="text-sm text-gray-600 font-medium">Top Spender</p>
            <p className="text-2xl font-bold text-transparent bg-clip-text bg-gradient-to-r from-amber-600 to-orange-600">
              {isLoading ? '...' : (topSpender ? `$${topSpender.amount.toFixed(2)}` : '$0.00')}
            </p>
          </div>
        </div>
        <div className="text-sm text-gray-500 truncate">
          {isLoading ? '...' : (topSpender ? topSpender.name : 'No data')}
        </div>
      </div>
    </div>
  );
};

export default CostKPICards;
