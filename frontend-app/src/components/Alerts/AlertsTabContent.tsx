/**
 * AlertsTabContent Component
 *
 * Reusable AWS Alerts body, extracted from AlertsPage so it can render both as the standalone
 * `/alerts` page and as the "Alerts" sub-tab of the Security Center page.
 *
 * Features:
 * - CloudGuard Alerts tab, one section per kind of alert: service limits (one row per limit, a
 *   chip per region), security findings, and cost spikes
 * - K8s Alerts tab aggregating EKS cluster alerts
 * - KPI cards showing alert severity counts and when the list was built
 * - Severity filter
 *
 * API Endpoints:
 * - GET /api/xamops/cloudguard/alerts?accountId={accountId}&forceRefresh={boolean}
 *
 * The topbar "Refresh Alerts" action is gated by `isActive` so only the currently visible owner
 * registers it — this prevents two mounted copies from fighting over useTopbar().setActions.
 */

import React, { useEffect, useMemo } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useAccounts } from '../../contexts/AccountContext';
import { useTopbar } from '../../contexts/TopbarContext';
import { useAlertsData } from '../../hooks/useAlertsData';
import { Icon } from '@iconify/react';
import AlertsKPICards from './AlertsKPICards';
import AlertsTabs from './AlertsTabs';
import { AlertsErrorBanner } from './AlertsErrorBanner';
import AlertsLimitsSection from './AlertsLimitsSection';
import AlertsSecuritySection from './AlertsSecuritySection';
import AlertsCostSection from './AlertsCostSection';
import EksAlertsTab from './EksAlertsTab';
import {
  categorise,
  groupQuotaAlerts,
  incompleteCategories,
  type SeverityFilter,
} from './alertGroups';

const SEVERITY_CHIPS: { value: SeverityFilter; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'CRITICAL', label: 'Critical' },
  { value: 'WARNING', label: 'Warning' },
];

interface AlertsTabContentProps {
  /** When false, this instance does not register the topbar "Refresh Alerts" action. */
  isActive?: boolean;
}

const AlertsTabContent: React.FC<AlertsTabContentProps> = ({ isActive = true }) => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    alerts,
    filteredAlerts,
    isLoading,
    isRefreshing,
    severityFilter,
    activeTab,
    alertCounts,
    errors,
    metadata,
    hasErrors,
    isPartialSuccess,
    loadAlerts,
    retryFailedServices,
    setSeverityFilter,
    setActiveTab,
    getAlertLink,
    quotaConsoleLink,
  } = useAlertsData({ selectedAccountId });

  // Totals per section come from the unfiltered list, so a section hidden by the filter can say
  // so instead of claiming the check found nothing.
  const all = useMemo(() => categorise(alerts), [alerts]);
  const shown = useMemo(() => categorise(filteredAlerts), [filteredAlerts]);
  const quotaGroups = useMemo(() => groupQuotaAlerts(shown.limits), [shown.limits]);
  const incomplete = useMemo(() => incompleteCategories(errors), [errors]);

  const chipCount = (value: SeverityFilter) =>
    value === 'ALL' ? alertCounts.total : value === 'CRITICAL' ? alertCounts.critical : alertCounts.warning;

  // Topbar Integration — only the active owner registers, so copies don't clobber each other.
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    if (!isActive) {
      clearActions();
      return;
    }
    setActions(
      <div className="flex items-center gap-3">
        <button
          onClick={() => loadAlerts(true)}
          disabled={isRefreshing}
          className="flex items-center gap-2 px-3 py-1.5 bg-[rgba(0,0,0,0.88)] text-white text-xs font-medium rounded-md hover:bg-black transition-colors disabled:opacity-50"
        >
          <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
          <span>{isRefreshing ? 'Scanning...' : 'Refresh Alerts'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [isActive, setActions, clearActions, isRefreshing, loadAlerts]);

  return (
    <div className="space-y-5">
      {/* Tabs */}
      <AlertsTabs activeTab={activeTab} onTabChange={setActiveTab} />

      <motion.div initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }} className="space-y-5">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="py-20 flex flex-col items-center justify-center text-center">
            <div className="w-14 h-14 bg-indigo-50 border border-indigo-100 rounded-xl flex items-center justify-center mx-auto mb-3">
              <Icon icon="mdi:bell-outline" width={28} className="text-indigo-500" />
            </div>
            <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">No Account Selected</h4>
            <p className="text-[13px] text-[rgba(0,0,0,0.45)]">Please select an account from the sidebar to view alerts.</p>
          </div>
        )}

        <AnimatePresence mode="wait">
        <motion.div key={activeTab} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
        {/* CloudGuard Tab Content */}
        {activeTab === 'cloudguard' && selectedAccountId && (
          <div className="space-y-5">
            {/* KPI Cards */}
            <AlertsKPICards alertCounts={alertCounts} isLoading={isLoading} metadata={metadata} />

            {/* Error Banner */}
            {hasErrors && (
              <AlertsErrorBanner
                errors={errors}
                onRetry={retryFailedServices}
                isPartialSuccess={isPartialSuccess}
              />
            )}

            {/* Severity filter */}
            {!isLoading && alertCounts.total > 0 && (
              <div className="flex items-center gap-2" role="group" aria-label="Filter by severity">
                <span className="text-[12px] text-[rgba(0,0,0,0.45)] mr-1">Show</span>
                {SEVERITY_CHIPS.map((chip) => {
                  const active = severityFilter === chip.value;
                  return (
                    <button
                      key={chip.value}
                      type="button"
                      onClick={() => setSeverityFilter(chip.value)}
                      aria-pressed={active}
                      className={`inline-flex items-center gap-1.5 px-3 py-1 rounded-full border text-[12px] font-medium transition-colors ${
                        active
                          ? 'bg-[rgba(0,0,0,0.88)] border-[rgba(0,0,0,0.88)] text-white'
                          : 'bg-white border-[rgba(0,0,0,0.15)] text-[rgba(0,0,0,0.7)] hover:border-[rgba(0,0,0,0.35)]'
                      }`}
                    >
                      {chip.label}
                      <span className={`tabular-nums ${active ? 'text-white/70' : 'text-[rgba(0,0,0,0.4)]'}`}>
                        {chipCount(chip.value)}
                      </span>
                    </button>
                  );
                })}
              </div>
            )}

            <AlertsLimitsSection
              groups={quotaGroups}
              total={all.limits.length}
              isLoading={isLoading}
              incomplete={incomplete.has('LIMITS')}
              consoleLink={quotaConsoleLink}
            />

            <AlertsSecuritySection
              alerts={shown.security}
              total={all.security.length}
              isLoading={isLoading}
              incomplete={incomplete.has('SECURITY')}
              getAlertLink={getAlertLink}
            />

            <AlertsCostSection
              alerts={shown.cost}
              total={all.cost.length}
              isLoading={isLoading}
              incomplete={incomplete.has('COST')}
              getAlertLink={getAlertLink}
            />
          </div>
        )}

        {/* K8s / EKS Alerts Tab */}
        {activeTab === 'k8s' && (
          <div>
            <EksAlertsTab />
          </div>
        )}
        </motion.div>
        </AnimatePresence>
      </motion.div>
    </div>
  );
};

export default AlertsTabContent;
