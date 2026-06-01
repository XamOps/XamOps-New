import React from 'react';
import { Icon } from '@iconify/react';
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
    <main className="flex-1 p-6 bg-white min-h-screen">
      <div className="space-y-6">
        <AzurePerformanceHeader
          selectedAccountId={selectedAccountId}
          isRefreshing={isRefreshing}
          onRefresh={handleRefresh}
        />

        {!selectedAccountId && !isLoading && (
          <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-16 text-center">
            <div className="flex flex-col items-center">
              <div className="w-14 h-14 bg-indigo-50 border border-indigo-100 rounded-xl flex items-center justify-center mx-auto mb-4">
                <Icon icon="mdi:chart-line" width={28} className="text-indigo-600" />
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
      </div>
    </main>
  );
};

export default AzurePerformancePage;
