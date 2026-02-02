/**
 * GCP Cost Management Page
 *
 * Converted from: gcp_cost.html
 *
 * Features:
 * - KPI cards (Total Cost, Categories, Top Spender)
 * - Group by filters (Service, Project, Tag)
 * - Cost breakdown visualization (donut chart)
 * - Detailed breakdown table
 * - ML-powered cost forecast
 * - All backend connections preserved
 * - Real-time data fetching
 */

import React, { useState, useEffect, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpCostData } from '../hooks/useGcpCostData';
import type { GcpCostFilters, GcpForecastDataPoint } from '../types/gcpCost';

// Component imports
import {
  GcpCostHeader,
  GcpCostKPICards,
  GcpCostBreakdownSection,
  GcpCostForecastChart,
} from '../components/GcpCost';

// Import styles
import '../styles/gcpCost.css';

const GcpCostPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Cost filters state
  const [filters, setFilters] = useState<GcpCostFilters>({
    groupBy: 'SERVICE',
    tagKey: 'owner',
  });

  // Forecast state
  const [forecastState, setForecastState] = useState<{
    isLoading: boolean;
    data: GcpForecastDataPoint[];
    service: string;
    periods: number;
    availableServices: string[];
  }>({
    isLoading: true,
    data: [],
    service: 'ALL',
    periods: 30,
    availableServices: ['ALL'],
  });

  // Get cost data using custom hook
  const {
    costData,
    isLoading,
    isRefreshing,
    loadForecastServices,
    loadForecast,
    refreshAll,
  } = useGcpCostData({
    selectedAccountId,
    filters,
  });

  // Update filters
  const handleFiltersChange = useCallback((updates: Partial<GcpCostFilters>) => {
    setFilters(prev => ({ ...prev, ...updates }));
  }, []);

  // Load forecast services and initial forecast
  const loadForecastData = useCallback(async () => {
    setForecastState(prev => ({ ...prev, isLoading: true }));

    try {
      // Load available services
      const services = await loadForecastServices();
      if (services.length > 0) {
        setForecastState(prev => ({ ...prev, availableServices: services }));
      }

      // Load forecast data
      const data = await loadForecast(forecastState.service, forecastState.periods);
      setForecastState(prev => ({
        ...prev,
        isLoading: false,
        data,
      }));
    } catch (err) {
      console.error('Failed to load forecast data:', err);
      setForecastState(prev => ({ ...prev, isLoading: false }));
    }
  }, [loadForecastServices, loadForecast, forecastState.service, forecastState.periods]);

  // Handle forecast service change
  const handleForecastServiceChange = useCallback(async (service: string) => {
    setForecastState(prev => ({ ...prev, isLoading: true, service }));

    try {
      const data = await loadForecast(service, forecastState.periods);
      setForecastState(prev => ({
        ...prev,
        isLoading: false,
        data,
      }));
    } catch (err) {
      console.error('Failed to load forecast:', err);
      setForecastState(prev => ({ ...prev, isLoading: false }));
    }
  }, [loadForecast, forecastState.periods]);

  // Handle forecast periods change
  const handleForecastPeriodsChange = useCallback(async (periods: number) => {
    setForecastState(prev => ({ ...prev, isLoading: true, periods }));

    try {
      const data = await loadForecast(forecastState.service, periods);
      setForecastState(prev => ({
        ...prev,
        isLoading: false,
        data,
      }));
    } catch (err) {
      console.error('Failed to load forecast:', err);
      setForecastState(prev => ({ ...prev, isLoading: false }));
    }
  }, [loadForecast, forecastState.service]);

  // Load initial forecast when account changes
  useEffect(() => {
    if (selectedAccountId) {
      loadForecastData();
    }
  }, [selectedAccountId]);

  // Refresh handler
  const handleRefresh = useCallback(async () => {
    await refreshAll();
    await loadForecastData();
  }, [refreshAll, loadForecastData]);

  return (
    <main className="gcp-cost-page">
      <GcpCostHeader
        isLoading={isRefreshing}
        onRefresh={handleRefresh}
      />

      <div className="gcp-cost-content">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="gcp-cost-no-account">
            <div className="gcp-cost-no-account-icon">
              <i className="fas fa-dollar-sign"></i>
            </div>
            <h4>No Account Selected</h4>
            <p>Please select a GCP account from the sidebar to view cost management data.</p>
          </div>
        )}

        {/* Content */}
        {selectedAccountId && (
          <>
            {/* KPI Cards */}
            <GcpCostKPICards
              costData={costData}
              isLoading={isLoading}
            />

            {/* Cost Breakdown Section */}
            <GcpCostBreakdownSection
              costData={costData}
              filters={filters}
              isLoading={isLoading}
              onFiltersChange={handleFiltersChange}
            />

            {/* Cost Forecast Chart */}
            <GcpCostForecastChart
              data={forecastState.data}
              isLoading={forecastState.isLoading}
              service={forecastState.service}
              periods={forecastState.periods}
              availableServices={forecastState.availableServices}
              onServiceChange={handleForecastServiceChange}
              onPeriodsChange={handleForecastPeriodsChange}
            />
          </>
        )}
      </div>
    </main>
  );
};

export default GcpCostPage;
