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

import React, { useState } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useFinOpsData } from '../hooks/useFinOpsData';
import type { DateFilter, NewBudget, NewSchedule, PaginationState } from '../types/finops';

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

export const FinOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Date filter state - initialized to match Cost page defaults
  const [dateFilter, setDateFilter] = useState<DateFilter>(getInitialDates());

  // Get FinOps data
  const {
    reportData,
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
      <div className="fixed inset-0 flex items-center justify-center bg-red-50 text-red-700 z-50 overflow-y-auto p-4">
        <div className="bg-white p-8 rounded-2xl shadow-2xl max-w-lg w-full">
          <div className="flex items-center justify-center w-20 h-20 mx-auto mb-6 bg-red-100 rounded-full shadow-inner">
            <i className="fas fa-exclamation-triangle text-red-600 text-3xl"></i>
          </div>
          <h2 className="text-2xl font-black text-center mb-2 text-gray-900">Failed to Load Report</h2>
          <p className="text-center text-gray-600 mb-8 font-medium">
            {error.message || 'An unexpected error occurred while fetching the FinOps data.'}
          </p>

          <div className="space-y-3">
            <button
              onClick={() => {
                console.log('[FinOpsPage] User clicked "Try Again"');
                window.location.reload();
              }}
              className="w-full bg-gradient-to-r from-red-600 to-red-700 text-white py-3 px-4 rounded-xl font-bold shadow-lg hover:shadow-red-200/50 hover:scale-[1.02] active:scale-[0.98] transition-all"
            >
              Try Again
            </button>
            <button
              onClick={() => {
                const details = document.getElementById('error-details');
                if (details) details.classList.toggle('hidden');
              }}
              className="w-full bg-gray-50 text-gray-500 py-3 px-4 rounded-xl font-semibold hover:bg-gray-100 transition-colors"
            >
              Show Technical Details
            </button>
          </div>

          <div id="error-details" className="hidden mt-6 p-4 bg-gray-900 rounded-xl overflow-x-auto">
            <pre className="text-xs text-green-400 font-mono">
              {JSON.stringify({
                message: error.message,
                stack: error.stack,
                timestamp: new Date().toISOString(),
                selectedAccountId: selectedAccountId
              }, null, 2)}
            </pre>
          </div>
        </div>
      </div>
    );
  }

  return (
    <main className="flex-1 flex flex-col min-h-screen bg-transparent">
      <FinOpsHeader
        dateFilter={dateFilter}
        isRefreshing={isRefreshing}
        onDateFilterChange={setDateFilter}
        onApplyDateFilter={applyDateFilter}
        onClearDateFilter={clearDateFilter}
        onRefresh={() => loadReport(true)}
      />

      <div className="flex-1 p-8 space-y-8">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="text-center py-12 rounded-2xl border-2 border-dashed border-gray-300 bg-gray-50/50">
            <div className="w-16 h-16 bg-gray-200 rounded-full flex items-center justify-center mx-auto mb-4">
              <i className="fas fa-chart-pie text-gray-400 text-2xl"></i>
            </div>
            <h4 className="text-lg font-bold text-gray-800 mb-2">No Account Selected</h4>
            <p className="text-gray-600">Please select an account from the sidebar to view FinOps report.</p>
          </div>
        )}

        {/* Loading State */}
        {selectedAccountId && isLoading && (
          <div className="space-y-8">
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-6">
              {[...Array(4)].map((_, i) => (
                <div key={i} className="animate-pulse bg-gray-200 h-32 w-full rounded-xl"></div>
              ))}
            </div>
            <div className="animate-pulse bg-gray-200 h-64 w-full rounded-xl"></div>
          </div>
        )}

        {/* Report Content */}
        {selectedAccountId && !isLoading && reportData && (
          <>
            {/* KPI Grid */}
            <FinOpsKPISection kpis={reportData.kpis} />

            {/* Budgets Section */}
            <FinOpsBudgets
              budgets={reportData.budgets}
              onCreateBudget={() => setIsBudgetModalOpen(true)}
              onScheduleReport={() => setIsScheduleModalOpen(true)}
              onDeleteBudget={handleDeleteBudget}
            />

            {/* Scheduled Reports */}
            {reportSchedules.length > 0 && (
              <FinOpsScheduledReports schedules={reportSchedules} onDeleteSchedule={handleDeleteSchedule} />
            )}

            {/* Charts Grid */}
            <FinOpsCharts costBreakdown={reportData.costBreakdown} />

            {/* Tagging Compliance Section */}
            <FinOpsTaggingCompliance
              taggingCompliance={reportData.taggingCompliance}
              pagination={taggingPagination}
              onPaginationChange={(updates) => updatePagination('tagging', updates)}
            />

            {/* Insights & Opportunities */}
            {(reportData.rightsizingRecommendations.length > 0 ||
              reportData.wastedResources.length > 0 ||
              reportData.costAnomalies.length > 0) && (
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
          </>
        )}
      </div>

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
