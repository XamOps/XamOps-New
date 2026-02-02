/**
 * GCP Rightsizing Page
 *
 * Converted from: gcp_rightsizing.html
 *
 * Features:
 * - KPI cards (Potential Savings, Current Costs, Recommendations, Top Service)
 * - Recommendations table with filtering and search
 * - Real-time data fetching from backend
 * - Force refresh capability
 * - LocalStorage caching for 24 hours
 * - Modern GCP styling with improved UI/UX
 */

import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpRightsizingData } from '../hooks/useGcpRightsizingData';

// Component imports
import {
  GcpRightsizingHeader,
  GcpRightsizingKPICards,
  GcpRightsizingTable,
} from '../components/GcpRightsizing';

// Import styles
import '../styles/gcpRightsizing.css';

const GcpRightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Get rightsizing data using custom hook
  const { recommendations, isLoading, isRefreshing, loadRecommendations } = useGcpRightsizingData({
    selectedAccountId,
  });

  // Refresh handler
  const handleRefresh = () => {
    loadRecommendations(true);
  };

  return (
    <main className="gcp-rightsizing-page">
      <GcpRightsizingHeader isRefreshing={isRefreshing} onRefresh={handleRefresh} />

      <div className="gcp-rightsizing-content">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="gcp-rightsizing-no-account">
            <div className="gcp-rightsizing-no-account-icon">
              <i className="fas fa-compress-arrows-alt"></i>
            </div>
            <h4>No Account Selected</h4>
            <p>Please select a GCP account from the sidebar to view rightsizing recommendations.</p>
          </div>
        )}

        {/* Loading State */}
        {selectedAccountId && isLoading && recommendations.length === 0 && (
          <div className="gcp-rightsizing-loading">
            {/* KPI Skeleton */}
            <div className="gcp-rightsizing-kpi-cards">
              {[...Array(4)].map((_, i) => (
                <div key={i} className="gcp-rightsizing-skeleton-card"></div>
              ))}
            </div>
            {/* Table Skeleton */}
            <div className="gcp-rightsizing-skeleton-table"></div>
          </div>
        )}

        {/* Rightsizing Data Content */}
        {selectedAccountId && !isLoading && (
          <>
            {/* KPI Cards */}
            <GcpRightsizingKPICards recommendations={recommendations} isLoading={isLoading} />

            {/* Recommendations Table */}
            <GcpRightsizingTable
              recommendations={recommendations}
              isLoading={isLoading}
              onRefresh={handleRefresh}
            />
          </>
        )}
      </div>
    </main>
  );
};

export default GcpRightsizingPage;
