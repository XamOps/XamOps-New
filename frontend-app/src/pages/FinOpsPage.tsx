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

import React, { useState, useEffect } from 'react';
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

export const FinOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Date filter state
  const [dateFilter, setDateFilter] = useState<DateFilter>({
    startDate: '',
    endDate: '',
  });

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
    setDateFilter({ startDate: '', endDate: '' });
    console.log('[FinOps] Date filter cleared, reloading report');
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

  // Reload report when date filter changes after clearing
  useEffect(() => {
    if (!dateFilter.startDate && !dateFilter.endDate && selectedAccountId) {
      loadReport(false);
    }
  }, [dateFilter, selectedAccountId, loadReport]);

  // Show error state
  if (error) {
    return (
      <div className="fixed inset-0 flex items-center justify-center bg-red-50 text-red-700 z-50">
        <div className="bg-white p-8 rounded-lg shadow-xl max-w-md w-full mx-4">
          <div className="flex items-center justify-center w-16 h-16 mx-auto mb-4 bg-red-100 rounded-full">
            <i className="fas fa-exclamation-triangle text-red-600 text-2xl"></i>
          </div>
          <h2 className="text-2xl font-bold text-center mb-4 text-gray-800">Error</h2>
          <p className="text-center text-gray-600 mb-6">{error.message}</p>
          <button
            onClick={() => window.location.reload()}
            className="w-full bg-red-600 text-white py-2 px-4 rounded-lg hover:bg-red-700 transition-colors"
          >
            Try Again
          </button>
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
