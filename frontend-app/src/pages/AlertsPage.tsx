/**
 * AlertsPage Component
 *
 * Main Alerts page with CloudGuard and Grafana alerts
 * Converted from: alerts.html
 *
 * Features:
 * - CloudGuard Alerts tab with quota and anomaly alerts
 * - Grafana Alerts tab with external alert monitoring
 * - KPI cards showing alert severity counts
 * - Filter by service functionality
 * - Real-time data fetching with caching
 *
 * API Endpoints:
 * - GET /api/xamops/cloudguard/alerts?accountId={accountId}&forceRefresh={boolean}
 * - GET /api/xamops/cloudguard/grafana-alerts
 */

import React, { useEffect } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useAlertsData } from '../hooks/useAlertsData';
import { Icon } from '@iconify/react';
import {
  AlertsKPICards,
  AlertsFilter,
  AlertsCloudGuardTable,
  AlertsGrafanaTable,
  AlertsEmptyState,
  AlertsTabs,
} from '../components/Alerts';
import { AlertsErrorBanner } from '../components/Alerts/AlertsErrorBanner';
import EksAlertsTab from '../components/Alerts/EksAlertsTab';

const AlertsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    filteredAlerts,
    grafanaAlerts,
    isLoading,
    isRefreshing,
    loadingGrafana,
    selectedService,
    activeTab,
    services,
    alertCounts,
    errors,
    hasErrors,
    isPartialSuccess,
    loadAlerts,
    retryFailedServices,
    setSelectedService,
    setActiveTab,
    getUsagePercentage,
    getUsageColor,
    getUsageBadgeClass,
    getAlertClass,
    getServiceIconClass,
    getAwsConsoleLink,
  } = useAlertsData({ selectedAccountId });

  // Topbar Integration
  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
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
  }, [setActions, clearActions, isRefreshing, loadAlerts]);

  return (
    <main className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen">
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
            <AlertsKPICards alertCounts={alertCounts} isLoading={isLoading} />

            {/* Error Banner */}
            {hasErrors && (
              <AlertsErrorBanner
                errors={errors}
                onRetry={retryFailedServices}
                isPartialSuccess={isPartialSuccess}
              />
            )}

            {/* Main Content Card */}
            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">
              <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center gap-3">
                <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
                  <Icon icon="mdi:chart-bar" width={16} className="text-indigo-600" />
                </div>
                <div>
                  <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">Critical Alerts Overview</h2>
                  <p className="text-[11px] text-[rgba(0,0,0,0.4)]">Monitor service limits and other critical alerts</p>
                </div>
              </div>

              {/* Filter */}
              {!isLoading && (
                <AlertsFilter
                  selectedService={selectedService}
                  services={services}
                  filteredCount={filteredAlerts.length}
                  onServiceChange={setSelectedService}
                />
              )}

              {/* Alerts Table or Empty State */}
              {!isLoading && filteredAlerts.length === 0 ? (
                <AlertsEmptyState onRefresh={() => loadAlerts(true)} />
              ) : (
                <AlertsCloudGuardTable
                  alerts={filteredAlerts}
                  isLoading={isLoading}
                  getUsagePercentage={getUsagePercentage}
                  getUsageColor={getUsageColor}
                  getUsageBadgeClass={getUsageBadgeClass}
                  getAlertClass={getAlertClass}
                  getServiceIconClass={getServiceIconClass}
                  getAwsConsoleLink={getAwsConsoleLink}
                />
              )}
            </div>
          </div>
        )}

        {/* Grafana Tab Content */}
        {activeTab === 'grafana' && selectedAccountId && (
          <div>
            <AlertsGrafanaTable alerts={grafanaAlerts} isLoading={loadingGrafana} />
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
    </main>
  );
};

export default AlertsPage;
