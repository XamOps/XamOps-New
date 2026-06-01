/**
 * Azure Cost Management Page
 *
 * Dedicated cost analysis page for Azure accounts, matching the pattern
 * of /cost (AWS) and /gcp-cost (GCP).
 *
 * Features:
 * - KPI cards: MTD spend, Forecasted, Last Month
 * - Cost breakdown by Service or Region (donut chart + table)
 * - 6-month cost history bar chart with ML anomaly detection
 * - Per-service monthly cost trend drawer (click a service row)
 * - ML-powered cost forecast chart (Prophet API)
 * - Manual refresh via POST /api/xamops/azure/dashboard/refresh
 */

import React, { useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureCostData } from '../hooks/useAzureCostData';
import {
  AzureCostHeader,
  AzureCostKPICards,
  AzureCostBreakdownSection,
  AzureCostHistoryChart,
  AzureCostForecastChart,
  AzureServiceTrendChart,
} from '../components/AzureCost';

const AzureCostPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    billingSummary,
    costByRegion,
    costHistory,
    kpis,
    isLoading,
    isRefreshing,
    refresh,
    forecast,
    isForecastLoading,
    serviceTrend,
    loadServiceTrend,
    clearServiceTrend,
  } = useAzureCostData(selectedAccountId);

  const handleRefresh = useCallback(async () => {
    await refresh();
  }, [refresh]);

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-transparent">
      <AzureCostHeader
        isLoading={isRefreshing}
        onRefresh={handleRefresh}
      />

      <div className="flex-1 p-8 space-y-8">
        {/* No Account Selected State */}
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-12 rounded-2xl border-2 border-dashed border-gray-300 bg-gray-50/50">
            <div className="w-16 h-16 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-dollar-sign text-gray-400 text-2xl"></i>
            </div>
            <h4 className="text-lg font-bold text-gray-800 mb-2">No Account Selected</h4>
            <p className="text-gray-600">Please select an Azure account from the sidebar to view cost management data.</p>
          </div>
        )}

        {/* Main Content */}
        {selectedAccountId && (
          <>
            {/* KPI Cards */}
            <AzureCostKPICards
              kpis={kpis}
              isLoading={isLoading}
            />

            {/* Cost Breakdown */}
            <AzureCostBreakdownSection
              billingSummary={billingSummary}
              costByRegion={costByRegion}
              isLoading={isLoading}
              onServiceClick={loadServiceTrend}
            />

            {/* Per-Service Monthly Trend Drawer (appears when a service row is clicked) */}
            {serviceTrend.dimension && (
              <AzureServiceTrendChart
                dimension={serviceTrend.dimension}
                data={serviceTrend.data}
                isLoading={serviceTrend.isLoading}
                onClose={clearServiceTrend}
              />
            )}

            {/* Cost History */}
            <AzureCostHistoryChart
              costHistory={costHistory}
              isLoading={isLoading}
            />

            {/* ML Cost Forecast */}
            <AzureCostForecastChart
              forecast={forecast}
              isLoading={isForecastLoading}
            />
          </>
        )}
      </div>
    </main>
  );
};

export default AzureCostPage;
