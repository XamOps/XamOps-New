import React from 'react';

interface AzureSecurityHeaderProps {
  selectedAccountId: string | null;
  isRefreshing: boolean;
  onRefresh: () => void;
}

const AzureSecurityHeader: React.FC<AzureSecurityHeaderProps> = ({
  selectedAccountId,
  isRefreshing,
  onRefresh,
}) => {
  return (
    <header className="glass-card border-b border-white/20 px-8 py-5 flex items-center justify-between shadow-sm">
      <div className="flex items-center gap-4">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-red-500 to-orange-600 flex items-center justify-center shadow-md">
          <i className="fas fa-shield-alt text-white text-lg"></i>
        </div>
        <div>
          <h1 className="text-xl font-black text-gray-900 tracking-tight">
            Security Center
          </h1>
          <p className="text-sm text-gray-500 font-medium">
            Microsoft Defender for Cloud insights
          </p>
        </div>

        {/* Defender badge */}
        <div className="ml-4 flex items-center gap-1.5 bg-red-50 border border-red-200 text-red-700 text-xs font-semibold px-3 py-1.5 rounded-full">
          <i className="fas fa-shield-alt text-xs"></i>
          <span>Powered by Microsoft Defender</span>
        </div>
      </div>

      <div className="flex items-center gap-3">
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isRefreshing}
            className="flex items-center gap-2 px-4 py-2 bg-red-600 hover:bg-red-700 disabled:bg-red-400 text-white text-sm font-semibold rounded-lg shadow-sm hover:shadow-md transition-all active:scale-95 transform"
          >
            <i className={`fas fa-sync-alt text-xs ${isRefreshing ? 'animate-spin' : ''}`}></i>
            <span>{isRefreshing ? 'Scanning...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    </header>
  );
};

export default AzureSecurityHeader;
