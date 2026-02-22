import React from 'react';

interface AzureAlertsHeaderProps {
  selectedAccountId: string | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AzureAlertsHeader: React.FC<AzureAlertsHeaderProps> = ({
  selectedAccountId,
  isRefreshing,
  onRefresh,
}) => {
  return (
    <header className="glass-card border-b border-white/20 px-8 py-5 flex items-center justify-between shadow-sm">
      <div className="flex items-center gap-4">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-orange-500 to-amber-600 flex items-center justify-center shadow-md">
          <i className="fas fa-bell text-white text-lg"></i>
        </div>
        <div>
          <h1 className="text-xl font-black text-gray-900 tracking-tight">
            Azure Monitor Alerts
          </h1>
          <p className="text-sm text-gray-500 font-medium">
            Infrastructure and operational alerts
          </p>
        </div>

        {/* Azure Monitor badge */}
        <div className="ml-4 flex items-center gap-1.5 bg-orange-50 border border-orange-200 text-orange-700 text-xs font-semibold px-3 py-1.5 rounded-full">
          <i className="fas fa-chart-line text-xs"></i>
          <span>Powered by Azure Monitor</span>
        </div>
      </div>

      <div className="flex items-center gap-3">
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-orange-600 hover:bg-orange-700 disabled:bg-orange-400 text-white text-sm font-semibold rounded-lg shadow-sm hover:shadow-md transition-all active:scale-95 transform"
          >
            <i className={`fas fa-sync-alt text-xs ${isRefreshing ? 'animate-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Refreshing...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    </header>
  );
};

export default AzureAlertsHeader;
