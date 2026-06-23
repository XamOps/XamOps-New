/**
 * FinOps Report Page
 *
 * Converted from: finops.html
 *
 * This is the main FinOps report page with cost optimization insights, budgets,
 * tagging compliance, and scheduled reports.
 *
 * Features:
 * - KPI cards (MTD spend, forecast, last month, savings)
 * - Budget management
 * - Scheduled email reports
 * - Cost breakdown charts (by service & region)
 * - Tagging compliance tracking
 * - Rightsizing recommendations
 * - Wasted resources detection
 * - Cost anomaly alerts
 * - Date range filtering
 * - Pagination for large tables
 */

import React, { useState, useRef, useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useFinOpsData } from '../hooks/useFinOpsData';
import type { DateFilter, NewBudget, NewSchedule, PaginationState } from '../types/finops';
import { Icon } from '@iconify/react';

// Component imports
import FinOpsHeader from '../components/FinOps/FinOpsHeader';
import FinOpsKPISection from '../components/FinOps/FinOpsKPISection';
import FinOpsBudgets from '../components/FinOps/FinOpsBudgets';
import FinOpsScheduledReports from '../components/FinOps/FinOpsScheduledReports';
import FinOpsCharts from '../components/FinOps/FinOpsCharts';
import FinOpsTaggingCompliance from '../components/FinOps/FinOpsTaggingCompliance';
import FinOpsInsights from '../components/FinOps/FinOpsInsights';
import BudgetModal from '../components/FinOps/BudgetModal';
import ScheduleModal from '../components/FinOps/ScheduleModal';
import Toast from '../components/FinOps/Toast';

// Helper to get initial date range (start of month to today) - matches Cost page defaults
const getInitialDates = (): DateFilter => {
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

export const FinOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Date filter state - initialized to match Cost page defaults
  const [dateFilter, setDateFilter] = useState<DateFilter>(getInitialDates());

  // Get FinOps data
  const {
    reportData,
    sectionLoading,
    reportSchedules,
    isLoading,
    isRefreshing,
    error,
    loadReport,
    createBudget,
    deleteBudget,
    createSchedule,
    deleteSchedule,
  } = useFinOpsData({
    selectedAccountId,
    dateFilter,
  });

  // Modal states
  const [isBudgetModalOpen, setIsBudgetModalOpen] = useState(false);
  const [isScheduleModalOpen, setIsScheduleModalOpen] = useState(false);
  const [isBudgetCreating, setIsBudgetCreating] = useState(false);
  const [isScheduleCreating, setIsScheduleCreating] = useState(false);

  // Toast state
  const [showSuccessToast, setShowSuccessToast] = useState(false);
  const [toastMessage, setToastMessage] = useState('');
  const [, setLastScheduleFrequency] = useState('');

  // Pagination states
  const [taggingPagination, setTaggingPagination] = useState<PaginationState>({
    currentPage: 1,
    itemsPerPage: 10,
  });

  const [rightsizingPagination, setRightsizingPagination] = useState<PaginationState>({
    currentPage: 1,
    itemsPerPage: 10,
  });

  const [wastedPagination, setWastedPagination] = useState<PaginationState>({
    currentPage: 1,
    itemsPerPage: 10,
  });

  // Date filter handlers
  const applyDateFilter = () => {
    if (!dateFilter.startDate || !dateFilter.endDate) {
      alert('Please select both start and end dates.');
      return;
    }

    if (dateFilter.startDate > dateFilter.endDate) {
      alert('Start date must be before end date.');
      return;
    }

    console.log('[FinOps] Applying date filter:', dateFilter.startDate, 'to', dateFilter.endDate);
    loadReport(false);
  };

  const clearDateFilter = () => {
    const initialDates = getInitialDates();
    setDateFilter(initialDates);
    console.log('[FinOps] Date filter reset to current month');
    // Note: loadReport will be called via useEffect in useFinOpsData when dateFilter changes
  };

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
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to create budget');
    } finally {
      setIsBudgetCreating(false);
    }
  };

  const handleDeleteBudget = async (budgetName: string) => {
    if (!confirm(`Are you sure you want to delete the budget "${budgetName}"?`)) {
      return;
    }

    try {
      await deleteBudget(budgetName);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete budget');
    }
  };

  // Schedule handlers
  const handleCreateSchedule = async (schedule: NewSchedule) => {
    if (!schedule.email || !schedule.frequency || !schedule.cloudAccountId) {
      alert('Please fill out all fields and ensure an account is selected.');
      return;
    }

    setIsScheduleCreating(true);

    try {
      await createSchedule(schedule);
      setIsScheduleModalOpen(false);

      // Show success toast
      setLastScheduleFrequency(schedule.frequency);
      setToastMessage(`Your ${schedule.frequency.toLowerCase()} report is set up.`);
      setShowSuccessToast(true);
      setTimeout(() => setShowSuccessToast(false), 4000);
    } catch (err) {
      console.error('Create schedule error:', err);
      alert(err instanceof Error ? err.message : 'Failed to create schedule');
    } finally {
      setIsScheduleCreating(false);
    }
  };

  const handleDeleteSchedule = async (scheduleId: number) => {
    if (!confirm('Are you sure you want to delete this report schedule?')) {
      return;
    }

    try {
      await deleteSchedule(scheduleId);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete schedule');
    }
  };

  // Pagination handlers
  const updatePagination = (
    type: 'tagging' | 'rightsizing' | 'wasted',
    updates: Partial<PaginationState>
  ) => {
    if (type === 'tagging') {
      setTaggingPagination((prev) => ({ ...prev, ...updates }));
    } else if (type === 'rightsizing') {
      setRightsizingPagination((prev) => ({ ...prev, ...updates }));
    } else {
      setWastedPagination((prev) => ({ ...prev, ...updates }));
    }
  };

  // Note: Report reloads automatically when dateFilter changes via useFinOpsData hook

  // Show error state
  if (error) {
    console.error('[FinOpsPage] Rendering error state:', error);
    return (
      <div className="flex-1 px-6 pt-4 pb-6 flex items-center justify-center">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-8 max-w-md w-full text-center">
          <div className="w-12 h-12 bg-red-50 rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:alert-circle-outline" width={24} color="#dc2626" />
          </div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">Failed to Load Report</h2>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-6">
            {error.message || 'An unexpected error occurred while fetching the FinOps data.'}
          </p>
          <button
            onClick={() => window.location.reload()}
            className="px-6 py-2.5 bg-blue-600 text-white text-sm font-semibold rounded-lg hover:bg-blue-700 transition-colors"
          >
            Try Again
          </button>
        </div>
      </div>
    );
  }

  return (
    <main className="flex-1 px-6 pt-4 pb-6 space-y-5">
      <FinOpsHeader
        dateFilter={dateFilter}
        isRefreshing={isRefreshing}
        onDateFilterChange={setDateFilter}
        onApplyDateFilter={applyDateFilter}
        onClearDateFilter={clearDateFilter}
        onRefresh={() => loadReport(true)}
      />

      {/* No Account State */}
      {!selectedAccountId && !isLoading && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-12 text-center">
          <div className="w-12 h-12 bg-slate-100 rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:chart-pie-outline" width={24} color="rgba(0,0,0,0.3)" />
          </div>
          <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</h4>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Please select an account from the sidebar to view FinOps report.</p>
        </div>
      )}

      {selectedAccountId && (
        <>
          {/* KPI Grid — shows as soon as billing-summary resolves (~3-5s) */}
          {sectionLoading.kpis ? (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
              {[...Array(4)].map((_, i) => (
                <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[110px] w-full rounded-xl" />
              ))}
            </div>
          ) : reportData && (
            <FinOpsKPISection kpis={reportData.kpis} />
          )}

          {/* Budgets Section */}
          <RevealSection>
            {sectionLoading.budgets ? (
              <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-40 w-full rounded-xl" />
            ) : reportData && (
              <FinOpsBudgets
                budgets={reportData.budgets}
                onCreateBudget={() => setIsBudgetModalOpen(true)}
                onScheduleReport={() => setIsScheduleModalOpen(true)}
                onDeleteBudget={handleDeleteBudget}
              />
            )}
          </RevealSection>

          {/* Scheduled Reports */}
          {reportSchedules.length > 0 && (
            <RevealSection>
              <FinOpsScheduledReports schedules={reportSchedules} onDeleteSchedule={handleDeleteSchedule} />
            </RevealSection>
          )}

          {/* Charts Grid — shows when cost-history + cost-by-region resolve */}
          <RevealSection delay={0.05}>
            {sectionLoading.charts ? (
              <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-64 w-full rounded-xl" />
            ) : reportData && (
              <FinOpsCharts
                costBreakdown={reportData.costBreakdown}
                costAnomalies={reportData.costAnomalies}
                selectedAccountId={selectedAccountId}
                dateFilter={dateFilter}
                onTimePresetChange={(startDate, endDate) => {
                  setDateFilter({ startDate, endDate });
                  loadReport(false);
                }}
              />
            )}
          </RevealSection>

          {/* Tagging Compliance — may be slow (full resource scan); shows its own skeleton */}
          <RevealSection delay={0.05}>
            {sectionLoading.tagging ? (
              <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-48 w-full rounded-xl" />
            ) : reportData && (
              <FinOpsTaggingCompliance
                taggingCompliance={reportData.taggingCompliance}
                pagination={taggingPagination}
                onPaginationChange={(updates) => updatePagination('tagging', updates)}
              />
            )}
          </RevealSection>

          {/* Insights — shows when rightsizing + wasted-resources + anomalies resolve */}
          <RevealSection delay={0.05}>
            {sectionLoading.insights ? (
              <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-64 w-full rounded-xl" />
            ) : reportData && (
              reportData.rightsizingRecommendations.length > 0 ||
              reportData.wastedResources.length > 0 ||
              reportData.costAnomalies.length > 0
            ) && reportData && (
              <FinOpsInsights
                costAnomalies={reportData.costAnomalies}
                rightsizingRecommendations={reportData.rightsizingRecommendations}
                wastedResources={reportData.wastedResources}
                rightsizingPagination={rightsizingPagination}
                wastedPagination={wastedPagination}
                onRightsizingPaginationChange={(updates) => updatePagination('rightsizing', updates)}
                onWastedPaginationChange={(updates) => updatePagination('wasted', updates)}
              />
            )}
          </RevealSection>
        </>
      )}

      {/* Modals */}
      <BudgetModal
        isOpen={isBudgetModalOpen}
        isCreating={isBudgetCreating}
        onClose={() => setIsBudgetModalOpen(false)}
        onSubmit={handleCreateBudget}
      />

      <ScheduleModal
        isOpen={isScheduleModalOpen}
        isCreating={isScheduleCreating}
        cloudAccountId={selectedAccountId}
        onClose={() => setIsScheduleModalOpen(false)}
        onSubmit={handleCreateSchedule}
      />

      {/* Toast Notification */}
      <Toast
        show={showSuccessToast}
        message={toastMessage}
        title="Schedule Created!"
        onClose={() => setShowSuccessToast(false)}
      />
    </main>
  );
};

export default FinOpsPage;
