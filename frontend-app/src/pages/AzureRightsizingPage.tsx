import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureRightsizingData } from '../hooks/useAzureRightsizingData';
import {
  AzureRightsizingHeader,
  AzureRightsizingKPICards,
  AzureRightsizingTable,
} from '../components/AzureRightsizing';

const AzureRightsizingPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { recommendations, status, isLoading, isRefreshing, loadRecommendations } =
    useAzureRightsizingData({ selectedAccountId });

  const handleRefresh = () => {
    loadRecommendations(true);
  };

  return (
    <main className="p-6 space-y-6">
      <AzureRightsizingHeader selectedAccountId={selectedAccountId} isRefreshing={isRefreshing} onRefresh={handleRefresh} />

      {!selectedAccountId && !isLoading && (
        <div className="glass-card rounded-xl p-16 text-center">
          <div className="flex flex-col items-center">
            <div className="w-16 h-16 bg-blue-100 rounded-full flex items-center justify-center mb-4">
              <i className="fas fa-compress-arrows-alt text-blue-500 text-2xl"></i>
            </div>
            <h3 className="text-lg font-bold text-gray-700 mb-1">No Account Selected</h3>
            <p className="text-sm text-gray-500 max-w-md">
              Please select an Azure account from the sidebar to view rightsizing recommendations.
            </p>
          </div>
        </div>
      )}

      {selectedAccountId && (
        <>
          <AzureRightsizingKPICards recommendations={recommendations} />
          <AzureRightsizingTable recommendations={recommendations} status={status} isLoading={isLoading} />
        </>
      )}
    </main>
  );
};

export default AzureRightsizingPage;
