/**
 * GCP FinOps Report Page
 *
 * Converted from: gcp_finops.html
 *
 * This is the main GCP FinOps report page with cost optimization insights, budgets,
 * and scheduled reports specifically for Google Cloud Platform accounts.
 *
 * Features:
 * - KPI cards (MTD spend, forecast, last month, savings)
 * - Budget management
 * - Scheduled email reports
 * - Cost breakdown charts (by service & region)
 * - Rightsizing recommendations
 * - Wasted resources detection
 * - Cost anomaly alerts
 * - Date range filtering
 */

import React, { useState, useEffect, useRef } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useGcpFinOpsData } from '../hooks/useGcpFinOpsData';
import type { DateFilter, GcpNewBudget, GcpNewSchedule } from '../types/gcpFinops';
import { Icon } from '@iconify/react';

// Component imports
import GcpFinOpsHeader from '../components/GcpFinOps/GcpFinOpsHeader';
import GcpFinOpsKPISection from '../components/GcpFinOps/GcpFinOpsKPISection';
import GcpFinOpsBudgets from '../components/GcpFinOps/GcpFinOpsBudgets';
import GcpFinOpsScheduledReports from '../components/GcpFinOps/GcpFinOpsScheduledReports';
import GcpFinOpsCharts from '../components/GcpFinOps/GcpFinOpsCharts';
import GcpFinOpsInsights from '../components/GcpFinOps/GcpFinOpsInsights';
import GcpBudgetModal from '../components/GcpFinOps/GcpBudgetModal';
import GcpScheduleModal from '../components/GcpFinOps/GcpScheduleModal';
import GcpToast from '../components/GcpFinOps/GcpToast';

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

export const GcpFinOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  // Date filter state
  const [dateFilter, setDateFilter] = useState<DateFilter>({
    startDate: '',
    endDate: '',
  });

  // Get GCP FinOps data
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
  } = useGcpFinOpsData({
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
  const [toastTitle, setToastTitle] = useState('');

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

    console.log('[GCP FinOps] Applying date filter:', dateFilter.startDate, 'to', dateFilter.endDate);
    loadReport(false);
  };

  const clearDateFilter = () => {
    setDateFilter({ startDate: '', endDate: '' });
    console.log('[GCP FinOps] Date filter cleared, reloading report');
  };

  // Budget handlers
  const handleCreateBudget = async (budget: GcpNewBudget) => {
    if (!budget.budgetName || !budget.budgetLimit) {
      alert('Please fill out all fields.');
      return;
    }

    setIsBudgetCreating(true);

    try {
      await createBudget(budget);
      setIsBudgetModalOpen(false);
      setToastTitle('Budget Created!');
      setToastMessage('Your GCP budget has been set up successfully.');
      setShowSuccessToast(true);
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
      setToastTitle('Budget Deleted');
      setToastMessage('The budget has been removed.');
      setShowSuccessToast(true);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete budget');
    }
  };

  // Schedule handlers
  const handleCreateSchedule = async (schedule: GcpNewSchedule) => {
    if (!schedule.email || !schedule.frequency || !schedule.cloudAccountId) {
      alert('Please fill out all fields and ensure an account is selected.');
      return;
    }

    // Validate custom time fields if CUSTOM frequency is selected
    if (schedule.frequency === 'CUSTOM') {
      if (
        schedule.customHour == null ||
        schedule.customHour < 0 ||
        schedule.customHour > 23
      ) {
        alert('Please enter a valid hour (0-23).');
        return;
      }
      if (
        schedule.customMinute == null ||
        schedule.customMinute < 0 ||
        schedule.customMinute > 59
      ) {
        alert('Please enter a valid minute (0-59).');
        return;
      }
      if (
        schedule.customFrequencyType === 'WEEKLY' &&
        (schedule.customDayOfWeek == null ||
          schedule.customDayOfWeek < 1 ||
          schedule.customDayOfWeek > 7)
      ) {
        alert('Please select a valid day of week.');
        return;
      }
      if (
        schedule.customFrequencyType === 'MONTHLY' &&
        (schedule.customDayOfMonth == null ||
          schedule.customDayOfMonth < 1 ||
          schedule.customDayOfMonth > 31)
      ) {
        alert('Please enter a valid day of month (1-31).');
        return;
      }
    }

    setIsScheduleCreating(true);

    try {
      await createSchedule(schedule);
      setIsScheduleModalOpen(false);

      // Show success toast with appropriate message
      let frequencyMsg = schedule.frequency.toLowerCase();
      if (schedule.frequency === 'CUSTOM') {
        const hour = String(schedule.customHour).padStart(2, '0');
        const minute = String(schedule.customMinute).padStart(2, '0');
        frequencyMsg = `custom ${schedule.customFrequencyType?.toLowerCase()} at ${hour}:${minute}`;
      }

      setToastTitle('Schedule Created!');
      setToastMessage(`Your ${frequencyMsg} report is set up.`);
      setShowSuccessToast(true);
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
      setToastTitle('Schedule Deleted');
      setToastMessage('The schedule has been removed.');
      setShowSuccessToast(true);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete schedule');
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
      <div className="flex-1 px-6 pt-4 pb-6 flex items-center justify-center">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-8 max-w-md w-full text-center">
          <div className="w-12 h-12 bg-red-50 rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:alert-circle-outline" width={24} color="#dc2626" />
          </div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">Failed to Load Report</h2>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-6">{error.message}</p>
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
      <GcpFinOpsHeader
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
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Please select a GCP account from the sidebar to view FinOps report.</p>
        </div>
      )}

      {/* Loading State */}
      {selectedAccountId && isLoading && (
        <>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
            {[...Array(4)].map((_, i) => (
              <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[110px] w-full rounded-xl"></div>
            ))}
          </div>
          <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-64 w-full rounded-xl"></div>
        </>
      )}

      {/* Report Content */}
      {selectedAccountId && !isLoading && reportData && (
        <>
          <GcpFinOpsKPISection kpis={reportData.kpis} />

          <RevealSection>
            <GcpFinOpsBudgets
              budgets={(reportData.budgets || []).map((b) => ({
                ...b,
                actualSpend: b.actualSpend || reportData.kpis?.monthToDateSpend || 0,
                forecastedSpend: b.forecastedSpend || reportData.kpis?.forecastedSpend || 0,
              }))}
              onCreateBudget={() => setIsBudgetModalOpen(true)}
              onScheduleReport={() => setIsScheduleModalOpen(true)}
              onDeleteBudget={handleDeleteBudget}
            />
          </RevealSection>

          {reportSchedules.length > 0 && (
            <RevealSection>
              <GcpFinOpsScheduledReports
                schedules={reportSchedules}
                onDeleteSchedule={handleDeleteSchedule}
              />
            </RevealSection>
          )}

          <RevealSection delay={0.05}>
            <GcpFinOpsCharts costBreakdown={reportData.costBreakdown} />
          </RevealSection>

          {(reportData.rightsizingRecommendations.length > 0 ||
            reportData.wastedResources.length > 0 ||
            reportData.costAnomalies.length > 0) && (
              <RevealSection delay={0.05}>
                <GcpFinOpsInsights
                  costAnomalies={reportData.costAnomalies}
                  rightsizingRecommendations={reportData.rightsizingRecommendations}
                  wastedResources={reportData.wastedResources}
                />
              </RevealSection>
            )}
        </>
      )}

      {/* Modals */}
      <GcpBudgetModal
        isOpen={isBudgetModalOpen}
        isCreating={isBudgetCreating}
        onClose={() => setIsBudgetModalOpen(false)}
        onSubmit={handleCreateBudget}
      />

      <GcpScheduleModal
        isOpen={isScheduleModalOpen}
        isCreating={isScheduleCreating}
        cloudAccountId={selectedAccountId}
        onClose={() => setIsScheduleModalOpen(false)}
        onSubmit={handleCreateSchedule}
      />

      {/* Toast Notification */}
      <GcpToast
        show={showSuccessToast}
        title={toastTitle}
        message={toastMessage}
        onClose={() => setShowSuccessToast(false)}
      />
    </main>
  );
};

export default GcpFinOpsPage;
