/**
 * FinOps Header Component
 *
 * Header with title, date filter, and refresh button
 */

import React from 'react';
import { useTopbar } from '../../contexts/TopbarContext';
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
  const { setActions, clearActions } = useTopbar();

  // Register FinOps actions in Topbar
  React.useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {/* Date Range Filter */}
        <div className="flex items-center gap-2 bg-slate-50 border border-slate-200 rounded-xl px-4 py-1.5 shadow-sm">
          <i className="fas fa-calendar text-slate-400 text-xs"></i>
          <input
            type="date"
            value={dateFilter.startDate}
            onChange={(e) => onDateFilterChange({ ...dateFilter, startDate: e.target.value })}
            className="border-0 focus:ring-0 text-xs text-slate-700 bg-transparent p-0 w-28"
            max={dateFilter.endDate || today}
          />
          <span className="text-slate-400 text-[10px] uppercase font-bold">to</span>
          <input
            type="date"
            value={dateFilter.endDate}
            onChange={(e) => onDateFilterChange({ ...dateFilter, endDate: e.target.value })}
            className="border-0 focus:ring-0 text-xs text-slate-700 bg-transparent p-0 w-28"
            min={dateFilter.startDate}
            max={today}
          />
          <button
            onClick={onApplyDateFilter}
            disabled={!dateFilter.startDate || !dateFilter.endDate}
            className="ml-2 px-3 py-1 bg-indigo-600 text-white text-[10px] font-bold rounded-lg hover:bg-indigo-700 transition-all disabled:opacity-50"
          >
            APPLY
          </button>
          {(dateFilter.startDate || dateFilter.endDate) && (
            <button onClick={onClearDateFilter} className="text-slate-400 hover:text-slate-600 ml-1">
              <i className="fas fa-times"></i>
            </button>
          )}
        </div>

        <button
          onClick={onRefresh}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-sm font-semibold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50"
        >
          <i className={`fas fa-sync-alt ${isRefreshing ? 'fa-spin' : ''}`} />
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [dateFilter, isRefreshing, onDateFilterChange, onApplyDateFilter, onClearDateFilter, onRefresh, setActions, clearActions, today]);

  return null;
};

export default FinOpsHeader;
