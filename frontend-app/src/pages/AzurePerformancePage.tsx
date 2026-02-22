import React from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzurePerformanceData } from '../hooks/useAzurePerformanceData';
import {
  AzurePerformanceHeader,
  AzurePerformanceKPICards,
  AzurePerformanceInsightsTable,
  AzurePerformanceStatusBanner,
} from '../components/AzurePerformance';

const AzurePerformancePage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const { performanceData, isLoading, isRefreshing, loadPerformanceData } =
    useAzurePerformanceData({ selectedAccountId });

  const handleRefresh = () => {
    loadPerformanceData(true);
  };

  const insights = performanceData?.insights ?? [];

  return (
    <main className="p-6 space-y-6">
      <AzurePerformanceHeader
        selectedAccountId={selectedAccountId}
        isRefreshing={isRefreshing}
        onRefresh={handleRefresh}
      />

      {!selectedAccountId && !isLoading && (
        <div className="glass-card rounded-xl p-16 text-center">
          <div className="flex flex-col items-center">
            <div className="w-16 h-16 bg-indigo-100 rounded-full flex items-center justify-center mb-4">
              <i className="fas fa-chart-line text-indigo-500 text-2xl"></i>
            </div>
            <h3 className="text-lg font-bold text-gray-700 mb-1">No Account Selected</h3>
            <p className="text-sm text-gray-500 max-w-md">
              Please select an Azure account from the sidebar to view performance insights.
            </p>
          </div>
        </div>
      )}

      {selectedAccountId && (
        <>
          {/* Status banner (PERMISSION_ERROR / API_ERROR / NO_DATA) */}
          <AzurePerformanceStatusBanner status={performanceData?.status ?? null} />

          {/* KPI cards */}
          <AzurePerformanceKPICards performanceData={performanceData} isLoading={isLoading} />

          {/* Insights table */}
          <AzurePerformanceInsightsTable insights={insights} isLoading={isLoading} />
        </>
      )}
    </main>
  );
};

export default AzurePerformancePage;
