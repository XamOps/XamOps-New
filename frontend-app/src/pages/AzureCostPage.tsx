/**
 * Azure Cost Management Page
 *
 * Dedicated cost analysis page for Azure accounts, matching the pattern
 * of /cost (AWS) and /gcp-cost (GCP).
 *
 * Features:
 * - KPI cards: MTD spend, Forecasted, Last Month
 * - Budgets and alerts
 * - Charges by service and by resource, at invoice grain, behind one switch
 * - Network spend pulled out of every service it is billed under
 * - Why spend changed: rate against usage, month on month
 * - Cost breakdown with a date range and six group-by dimensions, including resource group and tag
 * - 6-month cost history bar chart with month-end projection
 * - Per-service monthly cost trend drawer (click a service row)
 * - ML-powered cost forecast chart (Prophet API) with its own track record
 * - Manual refresh via POST /api/xamops/azure/dashboard/refresh
 *
 * <h2>Why four of these sections are AWS components</h2>
 * {@code CostDetailTabs}, {@code DataTransferSection} and {@code PriceVsVolumeSection} render Azure
 * data unchanged, because the Azure cost-insights endpoints return payloads field-for-field identical
 * to the AWS Cost and Usage Report ones. The GCP page made the same choice first. Copying them would
 * compile today and drift the moment one copy gained a column — and the point of this page is that
 * the two clouds answer the same questions the same way.
 */

import React, { useState, useCallback, useRef, useEffect, useMemo } from 'react';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureCostData, type AzureCostFilters } from '../hooks/useAzureCostData';
import { useAzureBudgetData } from '../hooks/useAzureBudgetData';
import type { NewBudget } from '../types/finops';
import {
  AzureCostHeader,
  AzureCostKPICards,
  AzureCostBreakdownSection,
  AzureCostHistoryChart,
  AzureCostForecastChart,
  AzureServiceTrendChart,
} from '../components/AzureCost';
import { AZURE_NETWORK_COPY } from '../components/AzureCost/azureNetworkCopy';
import {
  AZURE_CHARGES_COPY,
  AZURE_CHARGE_TYPES_COPY,
  AZURE_RESOURCES_COPY,
} from '../components/AzureCost/azureChargesCopy';
import CostBudgetDashboard from '../components/Cost/CostBudgetDashboard';
import BudgetModal from '../components/FinOps/BudgetModal';
import Toast from '../components/FinOps/Toast';
// Shared with the AWS and GCP cost pages, not copied. See the note above.
import CostDetailTabs from '../components/Cost/CostDetailTabs';
import DataTransferSection from '../components/Cost/DataTransferSection';
import {
  fetchAzureChargeTypes,
  fetchAzureChargesByService,
  fetchAzureNetworkCosts,
  fetchAzurePriceVsVolume,
  fetchAzureResourceChange,
  fetchAzureTopResources,
} from '../api/azureCostInsightsApi';
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

/** Start of the current month to today, matching the AWS page's default range. */
const getInitialDates = (): { startDate: string; endDate: string } => {
  const today = new Date();
  const startOfMonth = new Date(today.getFullYear(), today.getMonth(), 1);
  const format = (date: Date) =>
    `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
      date.getDate(),
    ).padStart(2, '0')}`;
  return { startDate: format(startOfMonth), endDate: format(today) };
};

const AzureCostPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const [filters, setFilters] = useState<AzureCostFilters>(() => ({
    ...getInitialDates(),
    groupBy: 'SERVICE',
  }));

  const handleFiltersChange = useCallback((updates: Partial<AzureCostFilters>) => {
    setFilters((prev) => ({ ...prev, ...updates }));
  }, []);

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
    forecastAccuracy,
    forecastService,
    forecastableServices,
    changeForecastService,
    serviceTrend,
    loadServiceTrend,
    clearServiceTrend,
    currency,
    breakdown,
    isBreakdownLoading,
    tagKeys,
  } = useAzureCostData(selectedAccountId, filters);

  // Budget data
  const {
    budgets,
    isLoading: budgetsLoading,
    error: budgetsError,
    createBudget,
    deleteBudget,
  } = useAzureBudgetData({ selectedAccountId });

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
      const created = await createBudget(budget);
      setIsBudgetModalOpen(false);
      // "Budget created successfully" was the whole message even when the budget could not alert
      // anyone, which is the property that makes a budget useful. Azure needs a contact address on a
      // subscription-scoped notification and this form does not collect one, so say so.
      const warning = (created as { warning?: string } | undefined)?.warning;
      setToastMessage(warning ?? 'Budget created successfully!');
      setShowSuccessToast(true);
      setTimeout(() => setShowSuccessToast(false), warning ? 9000 : 4000);
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

  const handleRefresh = useCallback(async () => {
    await refresh();
    // The insight sections are cached by react-query for half an hour, so a refresh that did not
    // clear them would leave the invoice, network and change sections showing pre-refresh figures
    // beside freshly refreshed KPI cards.
    invalidateCurInsights();
  }, [refresh]);

  // Map billingSummary to CostDataItem shape for the budget dashboard
  const costDataForBudget = useMemo(
    () => billingSummary.map(item => ({ name: item.serviceName, amount: item.monthToDateCost })),
    [billingSummary],
  );

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
              currency={currency}
            />

            {/* Budgets & Alerts Dashboard */}
            <RevealSection>
              {budgetsLoading ? (
                <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[400px] w-full rounded-xl" />
              ) : budgetsError ? (
                /* "Could not read budgets" is not "no budgets exist". The dashboard below invites the
                   user to create one, which is the wrong next step when the real problem is a missing
                   'Cost Management Reader' assignment or a subscription offer Azure does not serve. */
                <div className="rounded-xl border border-amber-200 bg-amber-50 px-5 py-4 flex items-start gap-3">
                  <Icon icon="mdi:alert-outline" width={20} color="#B45309" className="flex-shrink-0 mt-0.5" />
                  <div>
                    <p className="text-[14px] font-semibold text-amber-800 mb-0.5">Budgets Unavailable</p>
                    <p className="text-[13px] text-amber-800">{budgetsError}</p>
                  </div>
                </div>
              ) : (
                <CostBudgetDashboard
                  budgets={budgets}
                  costData={costDataForBudget}
                  costHistory={costHistory}
                  currency={currency}
                  onCreateBudget={() => setIsBudgetModalOpen(true)}
                  onDeleteBudget={handleDeleteBudget}
                />
              )}
            </RevealSection>

            {/* What was billed, at meter grain, in the invoice's own shape — and per resource, in a
                panel that opens from the service row itself. The breakdown section below stops at
                "Storage — ₹8,410"; this says which meter that was, and then which storage account.

                Per-resource cost needs a scope Cost Management will group by ResourceId and reports
                as unavailable otherwise, which the panel says in Azure's own terms rather than
                AWS's. */}
            <RevealSection>
              <CostDetailTabs
                accountId={selectedAccountId}
                chargesFetcher={fetchAzureChargesByService}
                resourcesFetcher={fetchAzureTopResources}
                insightPrefix="azure-"
                currency={currency}
                // Azure has no Cost and Usage Report and no Cost Explorer, and both panels default to
                // saying it does. See azureChargesCopy.
                chargesCopy={AZURE_CHARGES_COPY}
                resourcesCopy={AZURE_RESOURCES_COPY}
                changeFetcher={fetchAzurePriceVsVolume}
                detailFetcher={fetchAzureResourceChange}
                // Cost Management already returns AmortizedCost by its own choice, so a toggle here
                // would be a control over a decision made upstream. Explicit null rather than
                // omission, which would take the AWS default and render the control anyway.
                // The Connectivity tab is AWS-only. Azure has no topology endpoint at all;
                // GCP has a graph but no batch per-resource cost query and no identifier
                // reconciler, and the model is AWS-shaped — so the tab could only apologise. The
                // same reason `labels` is already off here. Explicit null rather than omission,
                // which takes the AWS default.
                connectivity={null}
                // Per-resource security findings are AWS-only in v1 (Security Center,
                // ComplianceOps and Performance all read AWS surfaces). Explicit null omits the tab.
                security={null}
                capabilitiesFetcher={null}
                // The ledger works: Cost Management groups by ChargeType, and nothing on this page
                // has ever shown that split.
                chargeTypesFetcher={fetchAzureChargeTypes}
                chargeTypesCopy={AZURE_CHARGE_TYPES_COPY}
                // Keep the account-wide fetcher and the panel's client-side filter: there is no
                // scoped per-service Azure endpoint, and omitting this would send a subscription id
                // to /cur-insights/service-resources, which 404s and would empty every panel.
                scopedResourcesFetcher={null}
                // Labels resolve through ResourceIdResolver, which arbitrates against the AWS
                // inventory table — an ARM resource id would match nothing there and the control
                // would appear to work while writing rows no page can read back. Same reason GCP
                // turns it off.
                labels={false}
              />
            </RevealSection>

            {/* Network costs, directly under the invoice. They are scattered across every service in
                the table below — NAT gateway processing under NAT Gateway, cross-region replication
                under Storage, peering under Bandwidth — so this is the only place the total is
                visible. Renders nothing when the subscription cannot be asked. */}
            <RevealSection>
              <DataTransferSection
                accountId={selectedAccountId}
                fetcher={fetchAzureNetworkCosts}
                insightName="azure-network"
                copy={AZURE_NETWORK_COPY}
                currency={currency}
              />
            </RevealSection>

            {/* Cost Breakdown */}
            <RevealSection>
              <AzureCostBreakdownSection
                billingSummary={billingSummary}
                costByRegion={costByRegion}
                isLoading={isLoading}
                onServiceClick={loadServiceTrend}
                filters={filters}
                onFiltersChange={handleFiltersChange}
                breakdown={breakdown}
                isBreakdownLoading={isBreakdownLoading}
                tagKeys={tagKeys}
                currency={currency}
              />
            </RevealSection>

            {/* Per-Service Monthly Trend Drawer (appears when a service row is clicked) */}
            {serviceTrend.dimension && (
              <RevealSection>
                <AzureServiceTrendChart
                  dimension={serviceTrend.dimension}
                  data={serviceTrend.data}
                  isLoading={serviceTrend.isLoading}
                  onClose={clearServiceTrend}
                  currency={currency}
                />
              </RevealSection>
            )}

            {/* Cost History */}
            <RevealSection delay={0.05}>
              <AzureCostHistoryChart
                costHistory={costHistory}
                isLoading={isLoading}
                currency={currency}
              />
            </RevealSection>

            {/* ML Cost Forecast */}
            <RevealSection delay={0.1}>
              <AzureCostForecastChart
                forecast={forecast}
                isLoading={isForecastLoading}
                currency={currency}
                accuracy={forecastAccuracy}
                service={forecastService}
                availableServices={forecastableServices}
                onServiceChange={changeForecastService}
              />
            </RevealSection>
          </>
        )}
      </div>

      {/* Budget Modal */}
      <BudgetModal
        isOpen={isBudgetModalOpen}
        isCreating={isBudgetCreating}
        availableServices={billingSummary.map(item => item.serviceName).sort()}
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

export default AzureCostPage;
