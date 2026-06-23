import React, { useState, useEffect, useRef } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import { useAzureFinOpsData } from '../hooks/useAzureFinOpsData';
import { AzureNewSchedule } from '../types/azureFinops';
import { Icon } from '@iconify/react';
import {
  AzureFinOpsHeader,
  AzureFinOpsKPICards,
  AzureFinOpsCostCharts,
  AzureFinOpsInsightsSection,
  AzureFinOpsScheduleSection,
  AzureFinOpsSavingsCallout,
} from '../components/AzureFinOps';
import { DialogRoot, DialogPanel } from '../components/ui';

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

const AzureFinOpsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const subscriptionId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

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

  if (error && !reportData) {
    return (
      <div className="flex-1 px-6 pt-4 pb-6 flex items-center justify-center">
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-8 max-w-md w-full text-center">
          <div className="w-12 h-12 bg-red-50 rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:alert-circle-outline" width={24} color="#dc2626" />
          </div>
          <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">Failed to Load Report</h2>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-6">{error}</p>
          <button
            onClick={handleRefresh}
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
      <AzureFinOpsHeader
        selectedAccountId={subscriptionId}
        isRefreshing={isRefreshing}
        onRefresh={handleRefresh}
      />

      {/* No account selected */}
      {!subscriptionId && !isLoading && (
        <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-12 text-center">
          <div className="w-12 h-12 bg-slate-100 rounded-xl flex items-center justify-center mx-auto mb-4">
            <Icon icon="mdi:chart-pie-outline" width={24} color="rgba(0,0,0,0.3)" />
          </div>
          <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</h3>
          <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
            Please select an Azure account from the sidebar to view the FinOps report.
          </p>
        </div>
      )}

      {subscriptionId && (
        <>
          <AzureFinOpsKPICards reportData={reportData} isLoading={isLoading} />
          <RevealSection>
            <AzureFinOpsSavingsCallout reportData={reportData} />
          </RevealSection>
          <RevealSection delay={0.05}>
            <AzureFinOpsCostCharts reportData={reportData} isLoading={isLoading} selectedAccountId={subscriptionId} />
          </RevealSection>
          <RevealSection delay={0.05}>
            <AzureFinOpsInsightsSection reportData={reportData} isLoading={isLoading} />
          </RevealSection>
          <RevealSection delay={0.05}>
            <AzureFinOpsScheduleSection
              schedules={schedules}
              onDeleteSchedule={onDeleteSchedule}
              onOpenScheduleModal={() => setIsScheduleModalOpen(true)}
            />
          </RevealSection>
        </>
      )}

      {/* Schedule Modal */}
      <DialogRoot isOpen={isScheduleModalOpen} onClose={() => setIsScheduleModalOpen(false)} maxWidth="max-w-md">
        <DialogPanel
          title={
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl flex items-center justify-center">
                <Icon icon="mdi:email-outline" width={20} color="#2563eb" />
              </div>
              <span className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Schedule FinOps Report</span>
            </div>
          }
          onClose={() => setIsScheduleModalOpen(false)}
          footer={
            <>
              <button
                type="button"
                onClick={() => setIsScheduleModalOpen(false)}
                className="px-5 py-2.5 rounded-lg text-sm font-medium bg-slate-100 text-[rgba(0,0,0,0.6)] hover:bg-slate-200 transition-colors"
              >
                Cancel
              </button>
              <button
                type="submit"
                form="azure-schedule-finops-form"
                disabled={isCreatingSchedule}
                className="px-5 py-2.5 rounded-lg text-sm font-semibold bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50 transition-colors flex items-center gap-2"
              >
                {isCreatingSchedule ? (
                  <>
                    <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                    Saving...
                  </>
                ) : 'Create Schedule'}
              </button>
            </>
          }
        >
          <form id="azure-schedule-finops-form" onSubmit={onCreateSchedule} className="space-y-5">
            <div>
              <label className="block text-[13px] font-semibold text-[rgba(0,0,0,0.6)] mb-1.5">Email Address</label>
              <input
                type="email"
                value={newSchedule.email}
                onChange={e => setNewSchedule({ ...newSchedule, email: e.target.value })}
                placeholder="report-recipient@example.com"
                className="w-full px-3 py-2.5 rounded-lg text-sm border border-[rgba(0,0,0,0.15)] focus:outline-none focus:ring-2 focus:ring-blue-500 transition-colors"
                required
              />
            </div>
            <div>
              <label className="block text-[13px] font-semibold text-[rgba(0,0,0,0.6)] mb-1.5">Frequency</label>
              <select
                value={newSchedule.frequency}
                onChange={e => setNewSchedule({ ...newSchedule, frequency: e.target.value })}
                className="w-full px-3 py-2.5 rounded-lg text-sm border border-[rgba(0,0,0,0.15)] focus:outline-none focus:ring-2 focus:ring-blue-500 transition-colors"
                required
              >
                <option value="" disabled>Select a frequency</option>
                <option value="DAILY">Daily (at 6 PM)</option>
                <option value="WEEKLY">Weekly (Friday at 6 PM)</option>
                <option value="MONTHLY">Monthly (30th at 6 PM)</option>
              </select>
            </div>
          </form>
        </DialogPanel>
      </DialogRoot>

      {/* Success toast */}
      {showToast && (
        <div className="fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-4 bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-lg">
          <div className="w-8 h-8 rounded-lg bg-[#1BE68B]/20 flex items-center justify-center flex-shrink-0">
            <Icon icon="mdi:check-circle" width={18} color="#158045" />
          </div>
          <div>
            <p className="text-[13px] font-semibold text-[rgba(0,0,0,0.88)]">Schedule Created!</p>
            <p className="text-[11px] text-[rgba(0,0,0,0.45)]">
              Your {lastFrequency.toLowerCase()} report is set up.
            </p>
          </div>
          <button onClick={() => setShowToast(false)} className="ml-2 text-[rgba(0,0,0,0.3)] hover:text-[rgba(0,0,0,0.6)] text-lg leading-none">&times;</button>
        </div>
      )}
    </main>
  );
};

export default AzureFinOpsPage;
