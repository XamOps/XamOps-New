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

import React, { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useCostData } from '../hooks/useCostData';
import { useFinOpsBudgetData } from '../hooks/useFinOpsBudgetData';
import type { CostFilters, HistoricalData, ForecastData, CostDataItem, ForecastAccuracyMetrics } from '../types/cost';
import type { NewBudget } from '../types/finops';
import type { ResourceLabel } from '../types/resourceLabels';
import { fetchLabels, fetchLabelCost } from '../services/resourceLabelsApi';
import type { LabelCostResponse } from '../services/resourceLabelsApi';

// Component imports
import CostHeader from '../components/Cost/CostHeader';
import CostKPICards from '../components/Cost/CostKPICards';
import CostBreakdownSection from '../components/Cost/CostBreakdownSection';
import HistoricalTrendChart from '../components/Cost/HistoricalTrendChart';
import CostForecastChart from '../components/Cost/CostForecastChart';
import CostBudgetDashboard from '../components/Cost/CostBudgetDashboard';
import BudgetModal from '../components/FinOps/BudgetModal';
import Toast from '../components/FinOps/Toast';


const RevealSection: React.FC<{ children: React.ReactNode; delay?: number }> = ({ children, delay = 0 }) => {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const timer = setTimeout(() => {
      const observer = new IntersectionObserver(([entry]) => {
        if (entry.isIntersecting) { el.classList.add('is-visible'); observer.unobserve(el); }
      }, { threshold: 0.06 });
      observer.observe(el);
      return () => observer.disconnect();
    }, 60);
    return () => clearTimeout(timer);
  }, []);
  return <div ref={ref} className="section-reveal" style={delay ? { transitionDelay: `${delay}s` } : undefined}>{children}</div>;
};

const CostPage: React.FC = () => {
  const { selectedAccountIds, accounts } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds.join(',') : null;

  /**
   * Account the CUR reload applies to, or null to hide the button. A CUR belongs to a
   * single AWS account, so a multi-account selection or a GCP/Azure account has nothing
   * to reload — hiding beats offering a button that can only error.
   */
  const curAccountId = React.useMemo(() => {
    if (selectedAccountIds.length !== 1) return null;
    const account = accounts.find(a => a.id === selectedAccountIds[0]);
    return (account?.provider ?? 'AWS') === 'AWS' ? selectedAccountIds[0] : null;
  }, [selectedAccountIds, accounts]);


  // Labels for the label cost filter
  const [allLabels, setAllLabels] = useState<ResourceLabel[]>([]);
  const [labelFilterId, setLabelFilterId] = useState<number | null>(null);
  const [labelCostData, setLabelCostData] = useState<LabelCostResponse | null>(null);
  const [labelFilterLoading, setLabelFilterLoading] = useState(false);

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
    accuracy: ForecastAccuracyMetrics | null;
  }>({
    isLoading: false,
    data: null,
    service: '',
    periods: 30,
    accuracy: null,
  });

  // Get cost data using custom hook
  const {
    costData,
    isLoading,
    isRefreshing,
    loadHistoricalData,
    loadForecast,
    loadForecastAccuracy,
    refreshAll,
  } = useCostData({
    selectedAccountId,
    filters,
  });

  // Budget & schedule data
  const {
    budgets,
    isLoading: budgetsLoading,
    createBudget,
    deleteBudget,
  } = useFinOpsBudgetData({ selectedAccountId });

  // Budget modal states
  const [isBudgetModalOpen, setIsBudgetModalOpen] = useState(false);
  const [isBudgetCreating, setIsBudgetCreating] = useState(false);

  // Toast state
  const [showSuccessToast, setShowSuccessToast] = useState(false);
  const [toastMessage, setToastMessage] = useState('');

  // Budget handlers
  const handleCreateBudget = async (budget: NewBudget) => {
    if (!budget.budgetName || !budget.budgetLimit) {
      alert('Please fill out all fields.');
      return;
    }
    setIsBudgetCreating(true);
    try {
      await createBudget(budget);
      setIsBudgetModalOpen(false);
      setToastMessage('Budget created successfully!');
      setShowSuccessToast(true);
      setTimeout(() => setShowSuccessToast(false), 4000);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to create budget');
    } finally {
      setIsBudgetCreating(false);
    }
  };

  const handleDeleteBudget = async (budgetName: string) => {
    if (!confirm(`Are you sure you want to delete the budget "${budgetName}"?`)) return;
    try {
      await deleteBudget(budgetName);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete budget');
    }
  };

  // Load labels once
  useEffect(() => {
    fetchLabels().then(setAllLabels).catch(() => {});
  }, []);

  // When label filter changes, call the backend endpoint for per-label cost
  const handleLabelFilterChange = useCallback(async (labelId: number | null) => {
    setLabelFilterId(labelId);
    if (!labelId || !selectedAccountId) {
      setLabelCostData(null);
      return;
    }
    setLabelFilterLoading(true);
    try {
      const accountKey = selectedAccountId.split(',')[0];
      const data = await fetchLabelCost(labelId, accountKey, filters.startDate, filters.endDate);
      setLabelCostData(data);
    } catch {
      setLabelCostData(null);
    } finally {
      setLabelFilterLoading(false);
    }
  }, [selectedAccountId, filters.startDate, filters.endDate]);

  // When date filters change while a label is active, re-fetch label cost
  useEffect(() => {
    if (labelFilterId && selectedAccountId) {
      const accountKey = selectedAccountId.split(',')[0];
      setLabelFilterLoading(true);
      fetchLabelCost(labelFilterId, accountKey, filters.startDate, filters.endDate)
        .then(setLabelCostData)
        .catch(() => setLabelCostData(null))
        .finally(() => setLabelFilterLoading(false));
    }
  }, [labelFilterId, selectedAccountId, filters.startDate, filters.endDate]);

  // Use backend label cost data when available, otherwise fall back to full costData
  const filteredCostData = useMemo((): CostDataItem[] => {
    if (labelFilterId && labelCostData) {
      return labelCostData.costs;
    }
    return costData;
  }, [costData, labelFilterId, labelCostData]);

  // Available services for forecast dropdown (respects label filter)
  const availableServices = filteredCostData.map(item => item.name).sort();

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

    const [data, accuracy] = await Promise.all([
      loadForecast(service, forecastState.periods, false),
      loadForecastAccuracy(service),
    ]);

    setForecastState(prev => ({
      ...prev,
      isLoading: false,
      data,
      accuracy,
    }));
  }, [loadForecast, loadForecastAccuracy, forecastState.periods]);

  // Load forecast on mount and when data changes
  useEffect(() => {
    if (selectedAccountId && costData.length > 0 && !forecastState.data) {
      setForecastState(prev => ({ ...prev, isLoading: true }));
      Promise.all([
        loadForecast(forecastState.service, forecastState.periods, false),
        loadForecastAccuracy(forecastState.service),
      ]).then(([data, accuracy]) => {
        setForecastState(prev => ({ ...prev, isLoading: false, data, accuracy }));
      });
    }
  }, [selectedAccountId, costData, forecastState.service, forecastState.periods, loadForecast, loadForecastAccuracy, forecastState.data]);

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
        curAccountId={curAccountId}
      />

      <div className="flex-1 p-8 space-y-8">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-12 rounded-xl border border-dashed border-[rgba(0,0,0,0.1)] bg-[rgba(0,0,0,0.02)]">
            <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-4">
              <Icon icon="mdi:currency-usd" width={28} color="rgba(0,0,0,0.25)" />
            </div>
            <h4 className="text-sm font-bold text-[rgba(0,0,0,0.7)] mb-2">No Account Selected</h4>
            <p className="text-sm text-[rgba(0,0,0,0.4)]">Please select an account from the sidebar to view cost management data.</p>
          </div>
        )}

        {/* Content */}
        {selectedAccountId && (
          <>
            {/* KPI Cards */}
            <CostKPICards
              costData={filteredCostData}
              filters={filters}
              isLoading={isLoading}
              labelFilterActive={!!labelFilterId}
            />

            {/* Budgets & Alerts Dashboard */}
            <RevealSection>
              {budgetsLoading ? (
                <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[400px] w-full rounded-xl" />
              ) : (
                <CostBudgetDashboard
                  budgets={budgets}
                  costData={filteredCostData}
                  onCreateBudget={() => setIsBudgetModalOpen(true)}
                  onDeleteBudget={handleDeleteBudget}
                />
              )}
            </RevealSection>

            {/* Cost Breakdown Section */}
            <RevealSection>
              <CostBreakdownSection
                costData={filteredCostData}
                filters={filters}
                isLoading={isLoading || labelFilterLoading}
                onFiltersChange={handleFiltersChange}
                onHistoricalClick={handleHistoricalClick}
                labels={allLabels}
                labelFilterId={labelFilterId}
                onLabelFilterChange={handleLabelFilterChange}
                labelFilterLoading={labelFilterLoading}
                labelResourceLevel={labelCostData?.resourceLevel}
                labelMessage={labelCostData?.message}
                labelTrackedResources={labelCostData?.trackedResources}
                labelApproximationWarning={labelCostData?.approximationWarning}
              />
            </RevealSection>

            {/* Historical Trend Chart */}
            {historicalState.dimension && (
              <RevealSection>
                <HistoricalTrendChart
                  dimension={historicalState.dimension}
                  data={historicalState.data}
                  isLoading={historicalState.isLoading}
                  onClose={handleCloseHistorical}
                />
              </RevealSection>
            )}

            {/* Cost Forecast Chart */}
            <RevealSection delay={0.05}>
              <CostForecastChart
                data={forecastState.data}
                isLoading={forecastState.isLoading}
                service={forecastState.service}
                availableServices={availableServices}
                onServiceChange={handleForecastServiceChange}
                accuracy={forecastState.accuracy}
              />
            </RevealSection>
          </>
        )}
      </div>

      {/* Budget Modal */}
      <BudgetModal
        isOpen={isBudgetModalOpen}
        isCreating={isBudgetCreating}
        availableServices={availableServices}
        onClose={() => setIsBudgetModalOpen(false)}
        onSubmit={handleCreateBudget}
      />

      {/* Toast Notification */}
      <Toast
        show={showSuccessToast}
        message={toastMessage}
        title="Budget Created!"
        onClose={() => setShowSuccessToast(false)}
      />
    </main>
  );
};

export default CostPage;
