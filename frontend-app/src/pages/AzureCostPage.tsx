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
    <main className="p-6 min-h-screen">
      <AzureCostHeader
        isLoading={isRefreshing}
        onRefresh={handleRefresh}
      />

      <div className="max-w-screen-2xl mx-auto">
        {/* No Account Selected State */}
        {!selectedAccountId && !isLoading && (
          <div className="flex flex-col items-center justify-center min-h-[60vh]">
            <div className="glass-card p-12 text-center max-w-md">
              <div className="w-20 h-20 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-2xl flex items-center justify-center mx-auto mb-6 shadow-lg">
                <i className="fas fa-dollar-sign text-white text-3xl"></i>
              </div>
              <h4 className="text-xl font-extrabold text-slate-900 tracking-tight mb-3">No Account Selected</h4>
              <p className="text-slate-500 text-sm font-semibold leading-relaxed">
                Please select an Azure account from the sidebar to view cost management data.
              </p>
            </div>
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
            <div className="mt-6">
              <AzureCostForecastChart
                forecast={forecast}
                isLoading={isForecastLoading}
              />
            </div>
          </>
        )}
      </div>
    </main>
  );
};

export default AzureCostPage;
