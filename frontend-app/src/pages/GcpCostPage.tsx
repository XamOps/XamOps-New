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

import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpCostData } from '../hooks/useGcpCostData';
import { useGcpBillingStatus } from '../hooks/useGcpBillingStatus';
import { useGcpBudgetData } from '../hooks/useGcpBudgetData';
import type { GcpCostFilters, GcpForecastDataPoint } from '../types/gcpCost';
import type { HistoricalData } from '../types/cost';
import type { NewBudget } from '../types/finops';

// Component imports
import {
  GcpCostHeader,
  GcpCostKPICards,
  GcpCostBreakdownSection,
  GcpCostForecastChart,
} from '../components/GcpCost';
import CostBudgetDashboard from '../components/Cost/CostBudgetDashboard';
import BudgetModal from '../components/FinOps/BudgetModal';
import Toast from '../components/FinOps/Toast';
// Shared with the AWS Cost page, not copied: the GhCP endpoint returns the identical payload, so
// one component serves both providers and cannot drift out of step with itself.
import DataTransferSection from '../components/Cost/DataTransferSection';
import CostDetailTabs from '../components/Cost/CostDetailTabs';
import HistoricalTrendChart from '../components/Cost/HistoricalTrendChart';
import {
  fetchGcpChargeTypes,
  fetchGcpChargesByService,
  fetchGcpNetworkCosts,
  fetchGcpPriceVsVolume,
  fetchGcpResourceChange,
  fetchGcpTopResources,
} from '../api/gcpCostInsightsApi';
import { GCP_NETWORK_COPY } from '../components/GcpCost/gcpNetworkCopy';
import {
  GCP_CHARGES_COPY,
  GCP_CHARGE_TYPES_COPY,
  GCP_RESOURCES_COPY,
} from '../components/GcpCost/gcpChargesCopy';
import { invalidateCurInsights } from '../hooks/useCurInsight';


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

const GcpCostPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Cost filters state. Start of the current month to today, matching the AWS and Azure defaults —
  // and matching what GcpCostService already defaulted to when the page sent no dates at all.
  const [filters, setFilters] = useState<GcpCostFilters>(() => {
    const today = new Date();
    const startOfMonth = new Date(today.getFullYear(), today.getMonth(), 1);
    const format = (date: Date) =>
      `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
        date.getDate(),
      ).padStart(2, '0')}`;
    return {
      groupBy: 'SERVICE',
      tagKey: 'owner',
      startDate: format(startOfMonth),
      endDate: format(today),
    };
  });

  // Historical trend drawer, opened by clicking a service row in the breakdown.
  const [historicalState, setHistoricalState] = useState<{
    isLoading: boolean;
    dimension: string | null;
    data: HistoricalData | null;
  }>({ isLoading: false, dimension: null, data: null });

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
    currency,
    forecastAccuracy,
    loadHistoricalTrend,
  } = useGcpCostData({
    selectedAccountId,
    filters,
  });

  // Why the cost data looks the way it does, so an empty chart can explain itself
  // instead of reading as "you spent nothing".
  const { billingStatus, reload: reloadBillingStatus } = useGcpBillingStatus({
    selectedAccountId,
  });

  // Budget data
  const {
    budgets,
    isLoading: budgetsLoading,
    createBudget,
    deleteBudget,
  } = useGcpBudgetData({ selectedAccountId });

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

  // Update filters
  const handleFiltersChange = useCallback((updates: Partial<GcpCostFilters>) => {
    setFilters(prev => ({ ...prev, ...updates }));
  }, []);

  // Open the daily trend for one service. Matches the AWS page's behaviour, using an endpoint that
  // has always existed on the GCP side and that nothing called.
  const handleHistoricalClick = useCallback(async (dimension: string) => {
    setHistoricalState({ isLoading: true, dimension, data: null });
    const data = await loadHistoricalTrend(dimension);
    setHistoricalState(prev =>
      // Discarded if the reader clicked another row while this was in flight, which would otherwise
      // let the first answer land last and show one service's trend under another's heading.
      prev.dimension === dimension ? { isLoading: false, dimension, data } : prev);
  }, [loadHistoricalTrend]);

  const handleCloseHistorical = useCallback(() => {
    setHistoricalState({ isLoading: false, dimension: null, data: null });
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

  // Refresh handler. Re-checks the billing source too, so this doubles as the
  // "I've enabled the export / fixed the permission, look again" action — otherwise a
  // customer who just fixed their setup would sit behind a cached failure.
  const handleRefresh = useCallback(async () => {
    await Promise.all([refreshAll(), reloadBillingStatus(true)]);
    await loadForecastData();
    // The insight sections are cached by react-query for half an hour, so a refresh that did not
    // clear them would leave the invoice, network and change sections showing pre-refresh figures
    // beside freshly refreshed KPI cards.
    invalidateCurInsights();
  }, [refreshAll, reloadBillingStatus, loadForecastData]);

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-transparent">
      <GcpCostHeader
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
            <p className="text-gray-600">Please select a GCP account from the sidebar to view cost management data.</p>
          </div>
        )}

        {/* Content */}
        {selectedAccountId && (
          <>
            {/* KPI Cards */}
            <GcpCostKPICards
              costData={costData}
              isLoading={isLoading}
              currency={currency}
            />

            {/* Budgets & Alerts Dashboard */}
            <RevealSection>
              {budgetsLoading ? (
                <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[400px] w-full rounded-xl" />
              ) : (
                <CostBudgetDashboard
                  budgets={budgets}
                  costData={costData}
                  currency={currency}
                  onCreateBudget={() => setIsBudgetModalOpen(true)}
                  onDeleteBudget={handleDeleteBudget}
                />
              )}
            </RevealSection>

            {/* What was billed, at SKU grain, in the invoice's own shape — and per resource, in a
                panel that opens from the service row itself. The breakdown section below stops at
                one line: "Compute Engine", one total. This says which SKU that was, and then which
                instance.

                Per-resource cost needs GCP's detailed export and reports as unavailable on the
                standard one, which the panel says in GCP's own terms rather than AWS's. */}
            <RevealSection>
              <CostDetailTabs
                accountId={selectedAccountId}
                chargesFetcher={fetchGcpChargesByService}
                resourcesFetcher={fetchGcpTopResources}
                insightPrefix="gcp-"
                currency={currency}
                // Both panels default to AWS wording — "your AWS bill", "no Cost and Usage Report" —
                // which this page inherited when it started passing its own fetchers.
                chargesCopy={GCP_CHARGES_COPY}
                resourcesCopy={GCP_RESOURCES_COPY}
                changeFetcher={fetchGcpPriceVsVolume}
                detailFetcher={fetchGcpResourceChange}
                // No unblended/amortized distinction in the BigQuery export, so there is nothing
                // to toggle. Explicit null rather than omission: omitting takes the AWS default,
                // which would render an AWS basis control over figures that have no such basis.
                capabilitiesFetcher={null}
                // The ledger, though — the export has a cost type on every line and a credit type
                // on every credit, and nothing on this page has ever shown that split.
                chargeTypesFetcher={fetchGcpChargeTypes}
                chargeTypesCopy={GCP_CHARGE_TYPES_COPY}
                // Keep the account-wide fetcher and the panel's client-side filter: BigQuery's
                // export has no scoped per-service equivalent on the backend, and omitting this
                // would send a GCP project id to /cur-insights/service-resources, which 404s and
                // would empty every service panel.
                scopedResourcesFetcher={null}
                // Labels resolve through ResourceIdResolver, which arbitrates against the AWS
                // inventory table — a GCP resource id would match nothing there and the control
                // would appear to work while writing rows no page can read back.
                labels={false}
              />
            </RevealSection>

            {/* Network costs, directly under the budget panel and above the breakdown. They are
                scattered across every service in the table below — Cloud NAT under Networking,
                cross-zone under whatever moved the bytes — so this is the only place the total is
                visible. Renders nothing when the project cannot answer, which is the case for
                reseller-billed projects and for any project without a readable export. */}
            <RevealSection>
              <DataTransferSection
                accountId={selectedAccountId}
                fetcher={fetchGcpNetworkCosts}
                insightName="gcp-network"
                copy={GCP_NETWORK_COPY}
                // The billing account's own currency, now that /costs/currency serves it. This was
                // hardcoded to INR with a comment saying the real value existed and was unreachable.
                currency={currency}
              />
            </RevealSection>

            {/* Cost Breakdown Section */}
            <RevealSection>
              <GcpCostBreakdownSection
                costData={costData}
                filters={filters}
                isLoading={isLoading}
                onFiltersChange={handleFiltersChange}
                billingStatus={billingStatus}
                currency={currency}
                onDimensionClick={handleHistoricalClick}
              />
            </RevealSection>

            {/* Daily trend for one service, opened from a breakdown row. */}
            {historicalState.dimension && (
              <RevealSection>
                <HistoricalTrendChart
                  dimension={historicalState.dimension}
                  data={historicalState.data}
                  isLoading={historicalState.isLoading}
                  onClose={handleCloseHistorical}
                  currency={currency}
                />
              </RevealSection>
            )}

            {/* Cost Forecast Chart */}
            <RevealSection delay={0.05}>
              <GcpCostForecastChart
                data={forecastState.data}
                isLoading={forecastState.isLoading}
                service={forecastState.service}
                periods={forecastState.periods}
                availableServices={forecastState.availableServices}
                onServiceChange={handleForecastServiceChange}
                onPeriodsChange={handleForecastPeriodsChange}
                currency={currency}
                accuracy={forecastAccuracy}
              />
            </RevealSection>
          </>
        )}
      </div>

      {/* Budget Modal */}
      <BudgetModal
        isOpen={isBudgetModalOpen}
        isCreating={isBudgetCreating}
        availableServices={costData.map(item => item.name).sort()}
        currency={currency}
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

export default GcpCostPage;
