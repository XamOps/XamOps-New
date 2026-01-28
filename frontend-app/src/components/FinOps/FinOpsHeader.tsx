/**
 * FinOps Header Component
 *
 * Header with title, date filter, and refresh button
 */

import React from 'react';
import type { DateFilter } from '../../types/finops';

interface FinOpsHeaderProps {
  dateFilter: DateFilter;
  isRefreshing: boolean;
  onDateFilterChange: (filter: DateFilter) => void;
  onApplyDateFilter: () => void;
  onClearDateFilter: () => void;
  onRefresh: () => void;
}

const FinOpsHeader: React.FC<FinOpsHeaderProps> = ({
  dateFilter,
  isRefreshing,
  onDateFilterChange,
  onApplyDateFilter,
  onClearDateFilter,
  onRefresh,
}) => {
  const today = new Date().toISOString().split('T')[0];

  return (
    <header className="bg-white/95 backdrop-blur-md border-b border-gray-200 h-20 flex items-center justify-between px-8 sticky top-0 z-10 transition-all duration-300">
      <div className="flex items-center space-x-4">
        <div className="w-12 h-12 bg-gradient-to-br from-green-500 to-teal-600 rounded-xl flex items-center justify-center shadow-lg shadow-green-500/20">
          <i className="fas fa-chart-pie text-white text-xl"></i>
        </div>
        <div>
          <h1 className="text-2xl font-bold text-gray-800">FinOps Report</h1>
          <p className="text-sm text-gray-600">Cloud financial operations dashboard</p>
        </div>
      </div>
      <div className="flex items-center space-x-4">
        {/* Date Range Filter */}
        <div className="flex items-center space-x-2 bg-white border border-gray-300 rounded-lg px-4 py-2 shadow-sm">
          <i className="fas fa-calendar text-gray-400"></i>
          <input
            type="date"
            value={dateFilter.startDate}
            onChange={(e) => onDateFilterChange({ ...dateFilter, startDate: e.target.value })}
            className="border-0 focus:ring-0 text-sm text-gray-700 bg-transparent"
            max={dateFilter.endDate || today}
            placeholder="Start Date"
          />
          <span className="text-gray-400">to</span>
          <input
            type="date"
            value={dateFilter.endDate}
            onChange={(e) => onDateFilterChange({ ...dateFilter, endDate: e.target.value })}
            className="border-0 focus:ring-0 text-sm text-gray-700 bg-transparent"
            min={dateFilter.startDate}
            max={today}
            placeholder="End Date"
          />
          <button
            onClick={onApplyDateFilter}
            disabled={!dateFilter.startDate || !dateFilter.endDate}
            className="ml-2 px-3 py-1 bg-indigo-600 text-white text-xs font-semibold rounded hover:bg-indigo-700 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
          >
            Apply
          </button>
          {(dateFilter.startDate || dateFilter.endDate) && (
            <button
              onClick={onClearDateFilter}
              className="text-gray-400 hover:text-gray-600"
              title="Clear filter"
            >
              <i className="fas fa-times"></i>
            </button>
          )}
        </div>
        <div className="flex items-center space-x-2">
          <div className="w-2 h-2 bg-green-500 rounded-full animate-pulse"></div>
          <span className="text-sm text-gray-600">Live data</span>
        </div>
        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center space-x-2 px-6 py-3 bg-gradient-to-r from-indigo-600 to-purple-600 text-white text-sm font-semibold rounded-lg shadow-md hover:shadow-lg transition-all disabled:opacity-50"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh Data'}</span>
        </button>
      </div>
    </header>
  );
};

export default FinOpsHeader;
