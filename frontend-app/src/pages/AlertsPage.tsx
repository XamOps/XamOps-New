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
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useAlertsData } from '../hooks/useAlertsData';
import clsx from 'clsx';
import {
  AlertsKPICards,
  AlertsFilter,
  AlertsCloudGuardTable,
  AlertsGrafanaTable,
  AlertsEmptyState,
  AlertsTabs,
} from '../components/Alerts';
import { AlertsErrorBanner } from '../components/Alerts/AlertsErrorBanner';

// Styles
import '../styles/alerts.css';

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
          className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl shadow-lg shadow-indigo-200 hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
        >
          <i className={clsx('fas fa-sync-alt', isRefreshing && 'animate-spin')}></i>
          <span>{isRefreshing ? 'Scanning...' : 'Refresh Alerts'}</span>
        </button>
      </div>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, loadAlerts]);

  return (
    <main className="alerts-page">
      {/* Tabs */}
      <AlertsTabs activeTab={activeTab} onTabChange={setActiveTab} />

      <div className="alerts-content">
        {/* No Account State */}
        {!selectedAccountId && !isLoading && (
          <div className="alerts-no-account">
            <div className="alerts-no-account-icon">
              <i className="fas fa-bell"></i>
            </div>
            <h4>No Account Selected</h4>
            <p>Please select an account from the sidebar to view alerts.</p>
          </div>
        )}

        {/* CloudGuard Tab Content */}
        {activeTab === 'cloudguard' && selectedAccountId && (
          <div className="alerts-cloudguard-content alerts-fade-in">
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
            <div className="alerts-main-card">
              <div className="alerts-card-header">
                <div className="alerts-card-header-left">
                  <div className="alerts-card-header-icon">
                    <i className="fas fa-chart-bar"></i>
                  </div>
                  <div>
                    <h2 className="alerts-card-title">Critical Alerts Overview</h2>
                    <p className="alerts-card-subtitle">
                      Monitor service limits and other critical alerts
                    </p>
                  </div>
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
          <div className="alerts-grafana-content alerts-fade-in">
            <AlertsGrafanaTable alerts={grafanaAlerts} isLoading={loadingGrafana} />
          </div>
        )}
      </div>
    </main>
  );
};

export default AlertsPage;
