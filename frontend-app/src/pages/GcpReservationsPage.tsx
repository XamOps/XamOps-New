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

import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpReservationsData } from '../hooks/useGcpReservationsData';

// Component imports
import GcpReservationsHeader from '../components/GcpReservations/GcpReservationsHeader';
import ActiveCudsSection from '../components/GcpReservations/ActiveCudsSection';
import CudRecommendationsSection from '../components/GcpReservations/CudRecommendationsSection';

export const GcpReservationsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

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

  return (
    <div className="gcp-reservations-page">
      <GcpReservationsHeader
        isRefreshing={isRefreshing}
        onRefresh={refreshAll}
      />

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
