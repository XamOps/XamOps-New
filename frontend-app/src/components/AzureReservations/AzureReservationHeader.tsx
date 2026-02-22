import React from 'react';

interface AzureReservationHeaderProps {
  selectedAccountId: string | null;
  isLoading: boolean;
  onRefresh: () => void;
}

const AzureReservationHeader: React.FC<AzureReservationHeaderProps> = ({
  selectedAccountId,
  isLoading,
  onRefresh,
}) => {
  return (
    <header className="glass-card border-b border-white/20 px-8 py-5 flex items-center justify-between shadow-sm">
      <div className="flex items-center gap-4">
        <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-blue-500 to-blue-700 flex items-center justify-center shadow-md">
          <i className="fas fa-file-contract text-white text-lg"></i>
        </div>
        <div>
          <h1 className="text-xl font-black text-gray-900 tracking-tight">
            Reservation Management
          </h1>
          <p className="text-sm text-gray-500 font-medium">
            Azure Reserved Instances &amp; Savings Plans
          </p>
        </div>

        {/* RBAC notice */}
        <div className="ml-4 flex items-center gap-1.5 bg-amber-50 border border-amber-200 text-amber-700 text-xs font-semibold px-3 py-1.5 rounded-full">
          <i className="fas fa-shield-alt text-xs"></i>
          <span>Requires Reservation Reader role</span>
        </div>
      </div>

      <div className="flex items-center gap-3">
        {selectedAccountId && (
          <button
            onClick={onRefresh}
            disabled={isLoading}
            className="flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-400 text-white text-sm font-semibold rounded-lg shadow-sm hover:shadow-md transition-all active:scale-95 transform"
          >
            <i className={`fas fa-sync-alt text-xs ${isLoading ? 'animate-spin' : ''}`}></i>
            <span>{isLoading ? 'Loading...' : 'Refresh'}</span>
          </button>
        )}
      </div>
    </header>
  );
};

export default AzureReservationHeader;
