/**
 * ReservationHeader Component
 *
 * Header for Reservation Management page with account info and refresh button
 *
 * Converted from: reservation.html (header section)
 */

import React from 'react';

interface ReservationHeaderProps {
  selectedAccountId: string | null;
  isLoading: boolean;
  onRefresh: () => void;
}

const ReservationHeader: React.FC<ReservationHeaderProps> = ({
  selectedAccountId,
  isLoading,
  onRefresh,
}) => {
  return (
    <header className="bg-white border-b border-gray-100 sticky top-0 z-20 px-8 py-5 flex items-center justify-between shadow-sm backdrop-blur-sm bg-white/95">
      <div className="flex items-center gap-4">
        <div className="w-12 h-12 bg-gradient-to-br from-indigo-100 to-purple-100 rounded-xl flex items-center justify-center shadow-md border border-indigo-200/50">
          <i className="fas fa-file-contract text-indigo-600 text-xl"></i>
        </div>
        <div>
          <h1 className="text-2xl font-black text-gray-900 tracking-tight">
            Reservation Management
          </h1>
          <p className="text-xs text-gray-500 font-semibold mt-1 flex items-center gap-2">
            <i className="fas fa-piggy-bank text-indigo-500 text-[10px]"></i>
            Optimize costs with Reserved Instances & Savings Plans
          </p>
        </div>
      </div>
      <div className="flex items-center gap-3">
        {selectedAccountId && (
          <span className="px-4 py-2 bg-gradient-to-br from-gray-50 to-gray-100 text-gray-700 text-xs rounded-lg font-mono border border-gray-200 shadow-sm">
            <i className="fas fa-hashtag text-indigo-500 text-[10px]"></i>
            <span className="ml-1">{selectedAccountId}</span>
          </span>
        )}
        <button
          onClick={onRefresh}
          disabled={isLoading}
          className="flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-indigo-600 to-purple-600 hover:from-indigo-700 hover:to-purple-700 text-white text-sm font-semibold rounded-lg shadow-md hover:shadow-lg transition-all active:scale-95 transform disabled:opacity-50 disabled:cursor-not-allowed"
        >
          <i className={`fas fa-sync-alt text-sm ${isLoading ? 'animate-spin' : ''}`}></i>
          <span>{isLoading ? 'Syncing...' : 'Refresh'}</span>
        </button>
      </div>
    </header>
  );
};

export default ReservationHeader;
