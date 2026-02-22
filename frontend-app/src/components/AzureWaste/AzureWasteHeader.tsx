import React from 'react';

interface AzureWasteHeaderProps {
  selectedAccountId: string | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AzureWasteHeader: React.FC<AzureWasteHeaderProps> = ({
  selectedAccountId,
  isRefreshing,
  onRefresh,
}) => {
  return (
    <header className="glass-card border-b border-white/20 px-8 py-5 flex items-center justify-between shadow-sm">
      <div className="flex items-center gap-4">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-500 to-blue-700 flex items-center justify-center shadow-md">
          <i className="fas fa-recycle text-white text-lg"></i>
        </div>
        <div>
          <h1 className="text-xl font-black text-gray-900 tracking-tight">
            Waste Management
          </h1>
          <p className="text-sm text-gray-500 font-medium">
            Detect idle and unused Azure resources
          </p>
        </div>

        {/* Azure Resource Manager badge */}
        <div className="ml-4 flex items-center gap-1.5 bg-blue-50 border border-blue-200 text-blue-700 text-xs font-semibold px-3 py-1.5 rounded-full">
          <i className="fas fa-cloud text-xs"></i>
          <span>Powered by Azure Resource Manager</span>
        </div>
      </div>

      <div className="flex items-center gap-3">
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white text-sm font-semibold rounded-lg shadow-sm hover:shadow-md transition-all active:scale-95 transform"
          >
            <i className={`fas fa-sync-alt text-xs ${isRefreshing ? 'animate-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Scanning...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    </header>
  );
};

export default AzureWasteHeader;
