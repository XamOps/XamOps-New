import React, { useState, useEffect } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureFinOpsData } from '../hooks/useAzureFinOpsData';
import { useTheme } from '../contexts/ThemeContext';
import { AzureNewSchedule } from '../types/azureFinops';
import clsx from 'clsx';
import {
  AzureFinOpsHeader,
  AzureFinOpsKPICards,
  AzureFinOpsCostCharts,
  AzureFinOpsInsightsSection,
  AzureFinOpsScheduleSection,
  AzureFinOpsSavingsCallout,
} from '../components/AzureFinOps';

const AzureFinOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const subscriptionId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const { theme } = useTheme();

  const {
    reportData,
    schedules,
    isLoading,
    isRefreshing,
    isCreatingSchedule,
    error,
    loadReport,
    handleCreateSchedule,
    handleDeleteSchedule,
  } = useAzureFinOpsData(subscriptionId);

  // Schedule modal state
  const [isScheduleModalOpen, setIsScheduleModalOpen] = useState(false);
  const [showToast, setShowToast] = useState(false);
  const [lastFrequency, setLastFrequency] = useState('');
  const [newSchedule, setNewSchedule] = useState<AzureNewSchedule>({
    email: '',
    frequency: '',
    cloudAccountId: subscriptionId,
  });

  useEffect(() => {
    setNewSchedule(prev => ({ ...prev, cloudAccountId: subscriptionId }));
  }, [subscriptionId]);

  const handleRefresh = () => loadReport(true);

  const onCreateSchedule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!newSchedule.email || !newSchedule.frequency || !newSchedule.cloudAccountId) {
      alert('Please fill out all fields and ensure a subscription is selected.');
      return;
    }
    try {
      await handleCreateSchedule(newSchedule);
      setIsScheduleModalOpen(false);
      setLastFrequency(newSchedule.frequency);
      setShowToast(true);
      setTimeout(() => setShowToast(false), 4000);
      setNewSchedule({ email: '', frequency: '', cloudAccountId: subscriptionId });
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to create schedule');
    }
  };

  const onDeleteSchedule = async (scheduleId: number) => {
    if (!confirm('Are you sure you want to delete this report schedule?')) return;
    try {
      await handleDeleteSchedule(scheduleId);
    } catch (err) {
      alert(err instanceof Error ? err.message : 'Failed to delete schedule');
    }
  };

  const inputCls = clsx(
    'w-full px-3 py-2.5 rounded-lg text-sm border transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500',
    theme === 'dark'
      ? 'bg-slate-700 border-slate-600 text-slate-100 placeholder-slate-400'
      : 'bg-white border-gray-300 text-gray-900 placeholder-gray-400'
  );

  const labelCls = clsx('block text-sm font-medium mb-1.5', theme === 'dark' ? 'text-slate-300' : 'text-gray-700');

  return (
    <main className="p-6 space-y-6">
      {/* Header */}
      <AzureFinOpsHeader
        selectedAccountId={subscriptionId}
        isRefreshing={isRefreshing}
        onRefresh={handleRefresh}
      />

      {/* No account selected */}
      {!subscriptionId && !isLoading && (
        <div className="glass-card rounded-xl p-16 text-center">
          <div className="flex flex-col items-center">
            <div className="w-16 h-16 bg-blue-100 rounded-full flex items-center justify-center mb-4">
              <i className="fas fa-chart-pie text-blue-500 text-2xl" />
            </div>
            <h3 className={clsx('text-lg font-bold mb-1', theme === 'dark' ? 'text-slate-200' : 'text-gray-700')}>
              No Account Selected
            </h3>
            <p className={clsx('text-sm max-w-md', theme === 'dark' ? 'text-slate-400' : 'text-gray-500')}>
              Please select an Azure account from the sidebar to view the FinOps report.
            </p>
          </div>
        </div>
      )}

      {/* Error state */}
      {subscriptionId && error && !reportData && (
        <div className={clsx(
          'glass-card rounded-xl p-6 border',
          theme === 'dark' ? 'border-red-800 bg-red-900/20' : 'border-red-200 bg-red-50'
        )}>
          <div className="flex items-center gap-3">
            <i className="fas fa-exclamation-circle text-red-500 text-xl" />
            <div>
              <p className={clsx('text-sm font-semibold', theme === 'dark' ? 'text-red-300' : 'text-red-700')}>
                Failed to load FinOps report
              </p>
              <p className={clsx('text-xs mt-0.5', theme === 'dark' ? 'text-red-400' : 'text-red-500')}>
                {error}
              </p>
            </div>
            <button
              onClick={handleRefresh}
              className="ml-auto text-sm font-medium text-red-600 hover:text-red-800"
            >
              Retry
            </button>
          </div>
        </div>
      )}

      {/* Main content */}
      {subscriptionId && (
        <>
          {/* Savings callout */}
          <AzureFinOpsSavingsCallout reportData={reportData} />

          {/* KPI Cards */}
          <AzureFinOpsKPICards reportData={reportData} isLoading={isLoading} />

          {/* Cost Charts */}
          <AzureFinOpsCostCharts reportData={reportData} isLoading={isLoading} />

          {/* Insights: Rightsizing + Waste */}
          <AzureFinOpsInsightsSection reportData={reportData} isLoading={isLoading} />

          {/* Schedule section */}
          <AzureFinOpsScheduleSection
            schedules={schedules}
            onDeleteSchedule={onDeleteSchedule}
            onOpenScheduleModal={() => setIsScheduleModalOpen(true)}
          />
        </>
      )}

      {/* Schedule Modal */}
      {isScheduleModalOpen && (
        <div
          className="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50"
          onClick={() => setIsScheduleModalOpen(false)}
        >
          <div
            className={clsx(
              'rounded-2xl p-8 w-full max-w-md mx-4 shadow-2xl',
              theme === 'dark' ? 'bg-slate-800 border border-slate-700' : 'bg-white'
            )}
            onClick={e => e.stopPropagation()}
          >
            <div className="flex items-center gap-3 mb-6">
              <div className="w-10 h-10 bg-gradient-to-br from-purple-500 to-pink-600 rounded-xl flex items-center justify-center shadow-lg">
                <i className="fas fa-envelope text-white" />
              </div>
              <h3 className={clsx('text-xl font-bold', theme === 'dark' ? 'text-slate-100' : 'text-gray-900')}>
                Schedule FinOps Report
              </h3>
            </div>

            <form onSubmit={onCreateSchedule} className="space-y-5">
              <div>
                <label className={labelCls}>Email Address</label>
                <input
                  type="email"
                  value={newSchedule.email}
                  onChange={e => setNewSchedule({ ...newSchedule, email: e.target.value })}
                  placeholder="report-recipient@example.com"
                  className={inputCls}
                  required
                />
              </div>
              <div>
                <label className={labelCls}>Frequency</label>
                <select
                  value={newSchedule.frequency}
                  onChange={e => setNewSchedule({ ...newSchedule, frequency: e.target.value })}
                  className={inputCls}
                  required
                >
                  <option value="" disabled>Select a frequency</option>
                  <option value="DAILY">Daily (at 6 PM)</option>
                  <option value="WEEKLY">Weekly (Friday at 6 PM)</option>
                  <option value="MONTHLY">Monthly (30th at 6 PM)</option>
                </select>
              </div>

              <div className="flex justify-end gap-3 pt-2">
                <button
                  type="button"
                  onClick={() => setIsScheduleModalOpen(false)}
                  className={clsx(
                    'px-5 py-2.5 rounded-lg text-sm font-medium transition-colors',
                    theme === 'dark'
                      ? 'bg-slate-700 text-slate-200 hover:bg-slate-600'
                      : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
                  )}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isCreatingSchedule}
                  className="px-5 py-2.5 rounded-lg text-sm font-medium bg-purple-600 text-white hover:bg-purple-700 disabled:opacity-50 transition-colors flex items-center gap-2"
                >
                  {isCreatingSchedule ? (
                    <>
                      <i className="fas fa-circle-notch fa-spin text-xs" />
                      Saving...
                    </>
                  ) : 'Create Schedule'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Success toast */}
      {showToast && (
        <div className="fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-4 bg-emerald-600 text-white rounded-xl shadow-2xl animate-fade-in">
          <i className="fas fa-check-circle text-xl" />
          <div>
            <p className="font-semibold text-sm">Schedule Created!</p>
            <p className="text-xs text-emerald-100">
              Your {lastFrequency.toLowerCase()} report is set up.
            </p>
          </div>
          <button onClick={() => setShowToast(false)} className="ml-2 text-emerald-200 hover:text-white text-lg leading-none">
            &times;
          </button>
        </div>
      )}
    </main>
  );
};

export default AzureFinOpsPage;
