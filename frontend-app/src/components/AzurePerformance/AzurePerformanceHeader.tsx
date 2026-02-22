import React from 'react';

interface AzurePerformanceHeaderProps {
  selectedAccountId: string | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AzurePerformanceHeader: React.FC<AzurePerformanceHeaderProps> = ({
  selectedAccountId,
  isRefreshing,
  onRefresh,
}) => {
  return (
    <header className="glass-card border-b border-white/20 px-8 py-5 flex items-center justify-between shadow-sm">
      <div className="flex items-center gap-4">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-purple-500 to-indigo-600 flex items-center justify-center shadow-md">
          <i className="fas fa-chart-line text-white text-lg"></i>
        </div>
        <div>
          <h1 className="text-xl font-black text-gray-900 tracking-tight">
            Performance Insights
          </h1>
          <p className="text-sm text-gray-500 font-medium">
            Azure Advisor performance recommendations
          </p>
        </div>

        {/* Advisor badge */}
        <div className="ml-4 flex items-center gap-1.5 bg-indigo-50 border border-indigo-200 text-indigo-700 text-xs font-semibold px-3 py-1.5 rounded-full">
          <i className="fas fa-lightbulb text-xs"></i>
          <span>Powered by Azure Advisor</span>
        </div>
      </div>

      <div className="flex items-center gap-3">
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-indigo-600 hover:bg-indigo-700 disabled:bg-indigo-400 text-white text-sm font-semibold rounded-lg shadow-sm hover:shadow-md transition-all active:scale-95 transform"
          >
            <i className={`fas fa-sync-alt text-xs ${isRefreshing ? 'animate-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    </header>
  );
};

export default AzurePerformanceHeader;
