/**
 * GCP Reservations Page
 *
 * Converted from: gcp_reservations.html
 *
 * This page displays GCP Committed Use Discounts (CUDs) including:
 * - Active CUDs
 * - CUD Recommendations
 *
 * Features:
 * - Displays active committed use discounts
 * - Shows purchase recommendations with savings estimates
 * - Real-time data fetching from backend
 * - Professional UI with improved styling
 * - Responsive design
 */

import React, { useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpReservationsData } from '../hooks/useGcpReservationsData';
import { useTopbar } from '../contexts/TopbarContext';
import clsx from 'clsx';

// Component imports
import ActiveCudsSection from '../components/GcpReservations/ActiveCudsSection';
import CudRecommendationsSection from '../components/GcpReservations/CudRecommendationsSection';

export const GcpReservationsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const { setActions, clearActions } = useTopbar();

  // Get GCP reservations data
  const {
    activeCuds,
    cudRecommendations,
    isLoadingActive,
    isLoadingRecommendations,
    isRefreshing,
    refreshAll,
  } = useGcpReservationsData({
    selectedAccountId,
  });

  // Topbar Integration
  useEffect(() => {
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={refreshAll}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Refreshing...' : 'Refresh Data'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, refreshAll]);

  return (
    <div className="gcp-reservations-page">
      {/* Header Removed - Integrated into Topbar */}

      <div className="gcp-reservations-content">
        <ActiveCudsSection
          activeCuds={activeCuds}
          isLoading={isLoadingActive}
        />

        <CudRecommendationsSection
          recommendations={cudRecommendations}
          isLoading={isLoadingRecommendations}
        />
      </div>
    </div>
  );
};

export default GcpReservationsPage;
