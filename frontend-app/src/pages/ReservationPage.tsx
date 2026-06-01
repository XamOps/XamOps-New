/**
 * Reservation Management Page
 *
 * Converted from: reservation.html
 *
 * This page provides comprehensive reservation management including:
 * - Reserved Instances inventory and utilization tracking
 * - Savings Plans monitoring
 * - Historical utilization and coverage trends
 * - Purchase recommendations with customizable filters
 * - Cost optimization insights
 *
 * All backend APIs, data fetching, and business logic preserved exactly as original.
 * Enhanced UI with modern React components and improved visual design.
 */

import React, { useEffect } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useReservationData } from '../hooks/useReservationData';
import { AriaTabGroup } from '../components/ui';
import type { TabType } from '../types/reservation';

// Component imports
import ReservationHeader from '../components/Reservation/ReservationHeader';
import ReservationMetrics from '../components/Reservation/ReservationMetrics';
import ReservationChart from '../components/Reservation/ReservationChart';
import ReservationInventory from '../components/Reservation/ReservationInventory';
import ReservationRecommendations from '../components/Reservation/ReservationRecommendations';
import ReservationSkeleton from '../components/Reservation/ReservationSkeleton';
import { useTabState } from '../hooks/useTabState';

export const ReservationPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Get reservation data
  const {
    data,
    isLoading,
    isRecsLoading,
    isSpRecsLoading,
    error,
    recFilters,
    setRecFilters,
    spRecFilters,
    setSpRecFilters,
    fetchReservationData,
    fetchPurchaseRecommendations,
    fetchSpRecommendations,
  } = useReservationData({
    selectedAccountId,
  });

  // Tab state
  const [activeTab, setActiveTab] = useTabState<TabType>('recommendations');

  useEffect(() => {
    if (activeTab === 'recommendations' && selectedAccountId && !isLoading) {
      fetchPurchaseRecommendations(false);
    }
  }, [activeTab, recFilters, selectedAccountId, isLoading]);

  useEffect(() => {
    if (activeTab === 'recommendations' && selectedAccountId && !isLoading) {
      fetchSpRecommendations(false);
    }
  }, [activeTab, spRecFilters, selectedAccountId, isLoading]);

  // Show error state
  if (error) {
    return (
      <main className="flex-1 flex items-center justify-center min-h-screen bg-[rgba(0,0,0,0.02)]">
        <div className="bg-white p-8 rounded-xl shadow-xl max-w-md w-full mx-4">
          <div className="flex items-center justify-center w-14 h-14 mx-auto mb-4 bg-red-100 border border-[rgba(0,0,0,0.08)] rounded-xl">
            <Icon icon="mdi:alert-outline" className="text-red-600 text-2xl" width={28} />
          </div>
          <h2 className="text-2xl font-bold text-center mb-4 text-[rgba(0,0,0,0.88)]">Error</h2>
          <p className="text-center text-[rgba(0,0,0,0.6)] mb-6">{error.message}</p>
          <button
            onClick={() => window.location.reload()}
            className="w-full bg-red-600 text-white py-2 px-4 rounded-md hover:bg-red-700 transition-colors"
          >
            Try Again
          </button>
        </div>
      </main>
    );
  }

  return (
    <main className="flex flex-col min-h-screen bg-transparent">
      <ReservationHeader
        selectedAccountId={selectedAccountId}
        isLoading={isLoading}
        onRefresh={() => fetchReservationData(true)}
      />

      <div className="flex-1 p-8 space-y-6 custom-scrollbar overflow-y-auto">
        {/* Loading State with Skeleton */}
        {isLoading && (!data.inventory || data.inventory.length === 0) ? (
          <>
            {/* Tab Navigation Skeleton */}
            <AriaTabGroup
              variant="pill"
              tabs={[
                { id: 'recommendations', label: 'Purchase Recommendations', icon: 'fas fa-lightbulb' },
                { id: 'overview',        label: 'Overview & Inventory',     icon: 'fas fa-chart-line' },
              ]}
              selectedKey={activeTab}
              onSelectionChange={(key) => setActiveTab(key as TabType)}
            />
            <ReservationSkeleton type={activeTab} />
          </>
        ) : (
          /* Content */
          <div className="space-y-6 fade-in">
            {/* Tab Navigation */}
            <AriaTabGroup
              variant="pill"
              tabs={[
                { id: 'recommendations', label: 'Purchase Recommendations', icon: 'fas fa-lightbulb' },
                { id: 'overview',        label: 'Overview & Inventory',     icon: 'fas fa-chart-line' },
              ]}
              selectedKey={activeTab}
              onSelectionChange={(key) => setActiveTab(key as TabType)}
            />

            {/* Overview Tab */}
            {activeTab === 'overview' && (
              <div className="space-y-6">
                {/* No Data Message */}
                {(!data.inventory || data.inventory.length === 0) &&
                  (!data.savingsPlans || data.savingsPlans.length === 0) && (
                    <div className="glass-card rounded-xl p-12 text-center">
                      <div className="flex flex-col items-center">
                        <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mb-6 shadow-lg">
                          <Icon icon="mdi:inbox-outline" className="text-[rgba(0,0,0,0.4)]" width={32} />
                        </div>
                        <h3 className="text-2xl font-black text-[rgba(0,0,0,0.88)] mb-3">
                          No Reservation Data Available
                        </h3>
                        <p className="text-[rgba(0,0,0,0.6)] text-base mb-2 max-w-md">
                          No Reserved Instances or Savings Plans were found for this account.
                        </p>
                        <p className="text-[rgba(0,0,0,0.5)] text-sm mb-6">
                          This could mean you haven't purchased any reservations yet, or data is
                          still being processed.
                        </p>
                        <button
                          onClick={() => fetchReservationData(true)}
                          className="flex items-center gap-2 px-6 py-3 bg-[rgba(0,0,0,0.88)] hover:bg-black text-white text-sm font-semibold rounded-md shadow-md hover:shadow-lg transition-all active:scale-95 transform"
                        >
                          <Icon icon="mdi:refresh" width={16} />
                          <span>Refresh Data</span>
                        </button>
                      </div>
                    </div>
                  )}

                {/* Metrics & Data */}
                {(data.inventory && data.inventory.length > 0) ||
                  (data.savingsPlans && data.savingsPlans.length > 0) ? (
                  <>
                    <ReservationMetrics
                      analysis={data.analysis}
                      inventory={data.inventory}
                      savingsPlans={data.savingsPlans}
                    />

                    <ReservationChart historicalData={data.historicalData} />

                    <ReservationInventory
                      inventory={data.inventory}
                      savingsPlans={data.savingsPlans}
                    />
                  </>
                ) : null}
              </div>
            )}

            {/* Recommendations Tab */}
            {activeTab === 'recommendations' && (
              <div className="space-y-6">
                <ReservationRecommendations
                  recommendations={data.purchaseRecommendations}
                  filters={recFilters}
                  isLoading={isRecsLoading}
                  onFilterChange={setRecFilters}
                  onFetchRecommendations={() => fetchPurchaseRecommendations(true)}
                  spRecommendations={data.savingsPlanRecommendations ?? []}
                  spFilters={spRecFilters}
                  isSpLoading={isSpRecsLoading}
                  onSpFilterChange={setSpRecFilters}
                  onFetchSpRecommendations={() => fetchSpRecommendations(true)}
                />
              </div>
            )}
          </div>
        )}
      </div>
    </main>
  );
};

export default ReservationPage;
