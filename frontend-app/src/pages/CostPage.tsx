/**
 * Cost Management Page
 *
 * Converted from: cost.html
 *
 * Features:
 * - KPI cards (Total Cost, Categories, Top Spender)
 * - Date range and group by filters
 * - Cost breakdown visualization (donut chart)
 * - Detailed breakdown table
 * - Historical trend chart (6-month)
 * - ML-powered cost forecast
 * - All backend connections preserved
 * - Real-time data fetching
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useCostData } from '../hooks/useCostData';
import type { CostFilters, HistoricalData, ForecastData } from '../types/cost';

// Component imports
import CostHeader from '../components/Cost/CostHeader';
import CostKPICards from '../components/Cost/CostKPICards';
import CostBreakdownSection from '../components/Cost/CostBreakdownSection';
import HistoricalTrendChart from '../components/Cost/HistoricalTrendChart';
import CostForecastChart from '../components/Cost/CostForecastChart';

const CostPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds.join(',') : null;

  // Initialize date range (start of month to today)
  const getInitialDates = () => {
    const today = new Date();
    const startOfMonth = new Date(today.getFullYear(), today.getMonth(), 1);

    const formatDate = (date: Date) => {
      const year = date.getFullYear();
      const month = String(date.getMonth() + 1).padStart(2, '0');
      const day = String(date.getDate()).padStart(2, '0');
      return `${year}-${month}-${day}`;
    };

    return {
      startDate: formatDate(startOfMonth),
      endDate: formatDate(today),
    };
  };

  // Cost filters state
  const [filters, setFilters] = useState<CostFilters>({
    ...getInitialDates(),
    groupBy: 'SERVICE',
    tagKey: 'Name',
  });

  // Historical trend state
  const [historicalState, setHistoricalState] = useState<{
    isLoading: boolean;
    dimension: string | null;
    data: HistoricalData | null;
  }>({
    isLoading: false,
    dimension: null,
    data: null,
  });

  // Forecast state
  const [forecastState, setForecastState] = useState<{
    isLoading: boolean;
    data: ForecastData | null;
    service: string;
    periods: number;
  }>({
    isLoading: false,
    data: null,
    service: '',
    periods: 30,
  });

  // Get cost data using custom hook
  const {
    costData,
    isLoading,
    isRefreshing,
    loadHistoricalData,
    loadForecast,
    refreshAll,
  } = useCostData({
    selectedAccountId,
    filters,
  });

  // Available services for forecast dropdown
  const availableServices = costData.map(item => item.name).sort();

  // Update filters
  const handleFiltersChange = useCallback((updates: Partial<CostFilters>) => {
    setFilters(prev => ({ ...prev, ...updates }));
  }, []);

  // Handle historical trend click
  const handleHistoricalClick = useCallback(async (dimension: string) => {
    setHistoricalState(prev => ({
      ...prev,
      isLoading: true,
      dimension,
    }));

    const data = await loadHistoricalData(dimension, false);

    setHistoricalState(prev => ({
      ...prev,
      isLoading: false,
      data,
    }));
  }, [loadHistoricalData]);

  // Close historical chart
  const handleCloseHistorical = useCallback(() => {
    setHistoricalState({
      isLoading: false,
      dimension: null,
      data: null,
    });
  }, []);

  // Handle forecast service change
  const handleForecastServiceChange = useCallback(async (service: string) => {
    setForecastState(prev => ({
      ...prev,
      isLoading: true,
      service,
    }));

    const data = await loadForecast(service, forecastState.periods, false);

    setForecastState(prev => ({
      ...prev,
      isLoading: false,
      data,
    }));
  }, [loadForecast, forecastState.periods]);

  // Load forecast on mount and when data changes
  useEffect(() => {
    if (selectedAccountId && costData.length > 0 && !forecastState.data) {
      setForecastState(prev => ({ ...prev, isLoading: true }));
      loadForecast(forecastState.service, forecastState.periods, false).then((data) => {
        setForecastState(prev => ({ ...prev, isLoading: false, data }));
      });
    }
  }, [selectedAccountId, costData, forecastState.service, forecastState.periods, loadForecast, forecastState.data]);

  // Refresh handler
  const handleRefresh = useCallback(async () => {
    await refreshAll();

    // Reload historical if open
    if (historicalState.dimension) {
      const data = await loadHistoricalData(historicalState.dimension, true);
      setHistoricalState(prev => ({ ...prev, data }));
    }

    // Reload forecast
    const forecastData = await loadForecast(forecastState.service, forecastState.periods, true);
    setForecastState(prev => ({ ...prev, data: forecastData }));
  }, [refreshAll, historicalState.dimension, forecastState.service, forecastState.periods, loadHistoricalData, loadForecast]);

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-transparent">
      <CostHeader
        isLoading={isRefreshing}
        onRefresh={handleRefresh}
      />

      <div className="flex-1 p-8 space-y-8">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-12 rounded-2xl border-2 border-dashed border-gray-300 bg-gray-50/50">
            <div className="w-16 h-16 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-dollar-sign text-gray-400 text-2xl"></i>
            </div>
            <h4 className="text-lg font-bold text-gray-800 mb-2">No Account Selected</h4>
            <p className="text-gray-600">Please select an account from the sidebar to view cost management data.</p>
          </div>
        )}

        {/* Content */}
        {selectedAccountId && (
          <>
            {/* KPI Cards */}
            <CostKPICards
              costData={costData}
              filters={filters}
              isLoading={isLoading}
            />

            {/* Cost Breakdown Section */}
            <CostBreakdownSection
              costData={costData}
              filters={filters}
              isLoading={isLoading}
              onFiltersChange={handleFiltersChange}
              onHistoricalClick={handleHistoricalClick}
            />

            {/* Historical Trend Chart */}
            {historicalState.dimension && (
              <HistoricalTrendChart
                dimension={historicalState.dimension}
                data={historicalState.data}
                isLoading={historicalState.isLoading}
                onClose={handleCloseHistorical}
              />
            )}

            {/* Cost Forecast Chart */}
            <CostForecastChart
              data={forecastState.data}
              isLoading={forecastState.isLoading}
              service={forecastState.service}
              availableServices={availableServices}
              onServiceChange={handleForecastServiceChange}
            />
          </>
        )}
      </div>
    </main>
  );
};

export default CostPage;
