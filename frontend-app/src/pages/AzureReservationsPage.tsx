import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureReservationsData } from '../hooks/useAzureReservationsData';
import { AriaTabGroup } from '../components/ui';
import type { AzureReservationTabType } from '../types/azureReservations';
import { useTabState } from '../hooks/useTabState';
import {
  AzureReservationHeader,
  AzureReservationMetrics,
  AzureReservationInventory,
  AzureReservationRecommendations,
} from '../components/AzureReservations';

export const AzureReservationsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { data, isLoading, error, refresh } = useAzureReservationsData(selectedAccountId);

  const [activeTab, setActiveTab] = useTabState<AzureReservationTabType>('overview');

  if (!selectedAccountId) {
    return (
      <main className="flex-1 flex items-center justify-center min-h-screen bg-gray-50">
        <div className="bg-white p-10 rounded-xl shadow-xl max-w-md w-full mx-4 text-center">
          <div className="flex items-center justify-center w-16 h-16 mx-auto mb-4 bg-blue-100 rounded-full">
            <i className="fas fa-file-contract text-blue-600 text-2xl"></i>
          </div>
          <h2 className="text-2xl font-bold mb-3 text-gray-800">No Azure Account Selected</h2>
          <p className="text-gray-500 text-sm">
            Select an Azure account from the sidebar to view reservation data.
          </p>
        </div>
      </main>
    );
  }

  if (error) {
    return (
      <main className="flex-1 flex items-center justify-center min-h-screen bg-gray-50">
        <div className="bg-white p-8 rounded-xl shadow-xl max-w-md w-full mx-4">
          <div className="flex items-center justify-center w-16 h-16 mx-auto mb-4 bg-red-100 rounded-full">
            <i className="fas fa-exclamation-triangle text-red-600 text-2xl"></i>
          </div>
          <h2 className="text-2xl font-bold text-center mb-4 text-gray-800">Error</h2>
          <p className="text-center text-gray-600 mb-6">{error.message}</p>
          <button
            onClick={refresh}
            className="w-full bg-red-600 text-white py-2 px-4 rounded-lg hover:bg-red-700 transition-colors"
          >
            Try Again
          </button>
        </div>
      </main>
    );
  }

  const hasData =
    data.reservations.length > 0 || data.savingsPlans.length > 0;

  return (
    <main className="flex flex-col min-h-screen bg-transparent">
      <AzureReservationHeader
        selectedAccountId={selectedAccountId}
        isLoading={isLoading}
        onRefresh={refresh}
      />

      <div className="flex-1 p-8 space-y-6 custom-scrollbar overflow-y-auto">
        {/* Tab navigation */}
        <AriaTabGroup
          variant="pill"
          tabs={[
            { id: 'overview',         label: 'Overview & Inventory', icon: 'fas fa-chart-line' },
            { id: 'recommendations',  label: 'Recommendations',      icon: 'fas fa-lightbulb' },
          ]}
          selectedKey={activeTab}
          onSelectionChange={(key) => setActiveTab(key as AzureReservationTabType)}
        />

        {/* Loading skeleton */}
        {isLoading && !hasData && (
          <div className="space-y-4 animate-pulse">
            <div className="grid grid-cols-4 gap-4">
              {[...Array(4)].map((_, i) => (
                <div key={i} className="glass-card rounded-xl h-24 bg-gray-100"></div>
              ))}
            </div>
            <div className="glass-card rounded-xl h-64 bg-gray-100"></div>
          </div>
        )}

        {/* Overview tab */}
        {!isLoading || hasData ? (
          <>
            {activeTab === 'overview' && (
              <div className="space-y-6 fade-in">
                <AzureReservationMetrics metrics={data.metrics} />

                {!hasData ? (
                  <div className="glass-card rounded-xl p-12 text-center shadow-sm">
                    <div className="flex flex-col items-center">
                      <div className="w-20 h-20 bg-gradient-to-br from-gray-100 to-gray-200 rounded-full flex items-center justify-center mb-5 shadow-sm">
                        <i className="fas fa-inbox text-gray-400 text-3xl"></i>
                      </div>
                      <h3 className="text-xl font-black text-gray-800 mb-2">
                        No Reservation Data Available
                      </h3>
                      <p className="text-gray-500 text-sm mb-2 max-w-md">
                        No Reserved Instances or Savings Plans were found for this subscription.
                      </p>
                      <p className="text-gray-400 text-xs mb-6">
                        If you have reservations, ensure the service principal has the{' '}
                        <strong>"Reservation Reader"</strong> role on the subscription.
                      </p>
                      <button
                        onClick={refresh}
                        className="flex items-center gap-2 px-6 py-3 bg-gradient-to-r from-blue-600 to-blue-700 hover:from-blue-700 hover:to-blue-800 text-white text-sm font-semibold rounded-lg shadow-md hover:shadow-lg transition-all active:scale-95 transform"
                      >
                        <i className="fas fa-sync-alt text-sm"></i>
                        <span>Refresh Data</span>
                      </button>
                    </div>
                  </div>
                ) : (
                  <AzureReservationInventory
                    reservations={data.reservations}
                    savingsPlans={data.savingsPlans}
                  />
                )}
              </div>
            )}

            {activeTab === 'recommendations' && (
              <div className="fade-in">
                <AzureReservationRecommendations recommendations={data.recommendations} />
              </div>
            )}
          </>
        ) : null}
      </div>
    </main>
  );
};

export default AzureReservationsPage;
