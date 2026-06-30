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

import React, { useState, useCallback, useRef, useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureCostData } from '../hooks/useAzureCostData';
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

  // Budget data
  const {
    budgets,
    isLoading: budgetsLoading,
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

  const handleRefresh = useCallback(async () => {
    await refresh();
  }, [refresh]);

  // Map billingSummary to CostDataItem shape for the budget dashboard
  const costDataForBudget = billingSummary.map(item => ({
    name: item.serviceName,
    amount: item.monthToDateCost,
  }));

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

            {/* Budgets & Alerts Dashboard */}
            <RevealSection>
              {budgetsLoading ? (
                <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[400px] w-full rounded-xl" />
              ) : (
                <CostBudgetDashboard
                  budgets={budgets}
                  costData={costDataForBudget}
                  onCreateBudget={() => setIsBudgetModalOpen(true)}
                  onDeleteBudget={handleDeleteBudget}
                />
              )}
            </RevealSection>

            {/* Cost Breakdown */}
            <RevealSection>
              <AzureCostBreakdownSection
                billingSummary={billingSummary}
                costByRegion={costByRegion}
                isLoading={isLoading}
                onServiceClick={loadServiceTrend}
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
                />
              </RevealSection>
            )}

            {/* Cost History */}
            <RevealSection delay={0.05}>
              <AzureCostHistoryChart
                costHistory={costHistory}
                isLoading={isLoading}
              />
            </RevealSection>

            {/* ML Cost Forecast */}
            <RevealSection delay={0.1}>
              <AzureCostForecastChart
                forecast={forecast}
                isLoading={isForecastLoading}
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
