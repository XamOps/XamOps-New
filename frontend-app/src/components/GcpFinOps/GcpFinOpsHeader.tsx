import React, { useEffect } from 'react';
import type { DateFilter } from '../../types/gcpFinops';
import { useTopbar } from '../../contexts/TopbarContext';

interface GcpFinOpsHeaderProps {
  dateFilter: DateFilter;
  isRefreshing: boolean;
  onDateFilterChange: (filter: DateFilter) => void;
  onApplyDateFilter: () => void;
  onClearDateFilter: () => void;
  onRefresh: () => void;
}

const GcpFinOpsHeader: React.FC<GcpFinOpsHeaderProps> = ({
  dateFilter,
  isRefreshing,
  onDateFilterChange,
  onApplyDateFilter,
  onClearDateFilter,
  onRefresh,
}) => {
  const { setActions, clearActions } = useTopbar();
  const today = new Date().toISOString().split('T')[0];

  useEffect(() => {
    setActions(
      <div className="flex items-center gap-4">
        {/* Date Range Filter */}
        <div className="flex items-center gap-2 bg-slate-100/50 p-1 rounded-xl border border-slate-200/50">
          <div className="flex items-center px-2 py-1 gap-2">
            <i className="fas fa-calendar text-slate-400 text-xs"></i>
            <input
              type="date"
              value={dateFilter.startDate}
              onChange={(e) => onDateFilterChange({ ...dateFilter, startDate: e.target.value })}
              className="bg-transparent border-0 focus:ring-0 text-xs font-semibold text-slate-700 p-0 w-24"
              max={dateFilter.endDate || today}
            />
            <span className="text-slate-400 text-[10px] font-bold uppercase">to</span>
            <input
              type="date"
              value={dateFilter.endDate}
              onChange={(e) => onDateFilterChange({ ...dateFilter, endDate: e.target.value })}
              className="bg-transparent border-0 focus:ring-0 text-xs font-semibold text-slate-700 p-0 w-24"
              min={dateFilter.startDate}
              max={today}
            />
          </div>

          <div className="flex items-center gap-1 pr-1">
            <button
              onClick={onApplyDateFilter}
              disabled={!dateFilter.startDate || !dateFilter.endDate}
              className="px-3 py-1 bg-indigo-600 text-white text-[10px] font-black uppercase tracking-wider rounded-lg hover:bg-indigo-700 transition-all disabled:opacity-50"
            >
              Apply
            </button>
            {(dateFilter.startDate || dateFilter.endDate) && (
              <button
                onClick={onClearDateFilter}
                className="w-6 h-6 flex items-center justify-center text-slate-400 hover:text-rose-500 transition-colors"
                title="Clear filter"
              >
                <i className="fas fa-times-circle text-xs"></i>
              </button>
            )}
          </div>
        </div>

        {/* Status & Refresh */}
        <div className="flex items-center gap-3 pl-2 border-l border-slate-200/50">
          <div className="flex items-center gap-2">
            <div className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse shadow-[0_0_8px_rgba(34,197,94,0.4)]"></div>
            <span className="text-[10px] font-bold text-slate-400 uppercase tracking-widest">Live</span>
          </div>

          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-white border border-slate-200 rounded-xl text-xs font-bold text-slate-600 hover:bg-slate-50 transition-all disabled:opacity-50 shadow-sm"
          >
            <i className={`fas fa-sync-alt ${isRefreshing ? 'animate-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
          </button>
        </div>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, dateFilter, isRefreshing, onDateFilterChange, onApplyDateFilter, onClearDateFilter, onRefresh, today]);

  return null;
};

export default GcpFinOpsHeader;
