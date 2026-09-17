/**
 * GCP Alerts Page
 *
 * Monitors GCP alerts across three categories:
 * - QUOTA alerts: VPC network limits and other resource quotas
 * - ANOMALY alerts: Cost anomalies detected in billing
 * - SECURITY alerts: Critical and high-severity security findings, grouped by category and resource
 *
 * API Endpoint:
 * - GET /api/xamops/gcp/cloudguard/alerts?accountId={projectId}&forceRefresh={bool}
 *   Returns a GcpAlertsResponse envelope: { alerts, generatedAt, degraded, degradedReasons,
 *   fromCache, totalFindings }.
 */

import React, { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { Icon } from '@iconify/react';
import { useAccounts } from '../contexts/AccountContext';
import { useTopbar } from '../contexts/TopbarContext';
import { useGcpAlertsData } from '../hooks/useGcpAlertsData';
import {
  listInvestigationsShared,
  type SreInvestigation,
} from '../hooks/useSreInvestigation';
import GcpAlertsKpiStrip from '../components/GcpAlerts/GcpAlertsKpiStrip';
import GcpAlertsStatusBanner from '../components/GcpAlerts/GcpAlertsStatusBanner';
import GcpAlertsFilters from '../components/GcpAlerts/GcpAlertsFilters';
import GcpAlertsTable from '../components/GcpAlerts/GcpAlertsTable';
import GcpAlertsPagination from '../components/GcpAlerts/GcpAlertsPagination';

const GcpAlertsPage: React.FC = () => {
  const { selectedAccountIds } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  const {
    alerts,
    filteredAlerts,
    pagedAlerts,
    alertCounts,
    services,
    generatedAt,
    degraded,
    degradedReasons,
    fromCache,
    totalFindings,
    isLoading,
    isRefreshing,
    selectedService,
    setSelectedService,
    searchTerm,
    setSearchTerm,
    activeTypes,
    toggleType,
    activeSeverities,
    toggleSeverity,
    resetFilters,
    filtersActive,
    page,
    setPage,
    pageSize,
    setPageSize,
    pageCount,
    loadAlerts,
    getUsagePercentage,
    getUsageColor,
    getUsageBadgeClass,
    getSecurityBadgeClass,
    getServiceIconClass,
    getGcpConsoleLink,
    getGcpActionText,
  } = useGcpAlertsData({ selectedAccountId });

  /**
   * The investigation list, fetched once for the whole page.
   *
   * SreInvestigateButton can look this up itself, but it is rendered once per row. Doing it here and
   * passing the answer down is what keeps a large table to a single request.
   */
  const [investigations, setInvestigations] = useState<SreInvestigation[]>([]);
  const [investigationsResolved, setInvestigationsResolved] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listInvestigationsShared()
      .then(data => {
        if (!cancelled) {
          setInvestigations(data);
          setInvestigationsResolved(true);
        }
      })
      .catch(() => {
        // Resolved with an empty list: every button then renders its default "investigate" state,
        // which is the correct affordance when we cannot tell whether one already ran.
        if (!cancelled) {
          setInvestigations([]);
          setInvestigationsResolved(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const { setActions, clearActions } = useTopbar();

  useEffect(() => {
    setActions(
      <button
        onClick={() => loadAlerts(true)}
        disabled={isRefreshing}
        className="flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-xs font-bold rounded-xl hover:bg-indigo-700 transition-all disabled:opacity-50 active:scale-95"
      >
        <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
        <span>{isRefreshing ? 'Scanning...' : 'Refresh Alerts'}</span>
      </button>
    );
    return () => clearActions();
  }, [setActions, clearActions, isRefreshing, loadAlerts]);

  const showTable = !isLoading && pagedAlerts.length > 0;
  const showEmpty = !isLoading && filteredAlerts.length === 0;

  return (
    <div className="flex-1 px-6 pt-4 pb-6 bg-white min-h-screen">
      <motion.div
        initial={{ opacity: 0, y: 10 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}
        className="space-y-5"
      >
        {/* No Account Selected */}
        {!selectedAccountId && !isLoading && (
          <div className="flex-1 flex items-center justify-center py-24">
            <div className="text-center max-w-md">
              <div className="w-16 h-16 rounded-xl bg-indigo-50 border border-indigo-100 flex items-center justify-center mx-auto mb-5">
                <Icon icon="mdi:bell-outline" width={32} className="text-indigo-500" />
              </div>
              <h3 className="text-[16px] font-semibold text-[rgba(0,0,0,0.88)] mb-2">
                No GCP Account Selected
              </h3>
              <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
                Please select a GCP account from the sidebar to view alerts.
              </p>
            </div>
          </div>
        )}

        {selectedAccountId && (
          <>
            <GcpAlertsKpiStrip counts={alertCounts} isLoading={isLoading} />

            {!isLoading && (
              <GcpAlertsStatusBanner
                degraded={degraded}
                degradedReasons={degradedReasons}
                generatedAt={generatedAt}
                fromCache={fromCache}
                totalFindings={totalFindings}
                rowCount={alerts.length}
                onRetry={() => loadAlerts(true)}
                isRefreshing={isRefreshing}
              />
            )}

            {/* Main Table Card */}
            <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm overflow-hidden">

              {/* Card Header */}
              <div className="px-5 py-4 border-b border-[rgba(0,0,0,0.06)] flex items-center justify-between flex-wrap gap-3">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-lg bg-indigo-50 border border-indigo-100 flex items-center justify-center">
                    <Icon icon="mdi:chart-bar" width={16} className="text-indigo-600" />
                  </div>
                  <div>
                    <h2 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)]">
                      GCP Alerts Overview
                    </h2>
                    <p className="text-[11px] text-[rgba(0,0,0,0.4)]">
                      Monitor quotas, cost anomalies, and security findings
                    </p>
                  </div>
                </div>
              </div>

              <GcpAlertsFilters
                services={services}
                selectedService={selectedService}
                setSelectedService={setSelectedService}
                searchTerm={searchTerm}
                setSearchTerm={setSearchTerm}
                activeTypes={activeTypes}
                toggleType={toggleType}
                activeSeverities={activeSeverities}
                toggleSeverity={toggleSeverity}
                resetFilters={resetFilters}
                filtersActive={filtersActive}
                resultCount={filteredAlerts.length}
                totalCount={alerts.length}
              />

              <AnimatePresence mode="wait">
                {isLoading && (
                  <motion.div key="loading" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} transition={{ duration: 0.15 }}>
                    <div className="p-5 space-y-3">
                      {[...Array(6)].map((_, i) => (
                        <div key={i} className="h-10 bg-[rgba(0,0,0,0.04)] rounded animate-pulse" />
                      ))}
                    </div>
                  </motion.div>
                )}

                {showEmpty && (
                  <motion.div key="empty" initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.18, ease: 'easeOut' }}>
                    {/*
                      Three distinct empty states, because they call for different responses. Filters
                      hiding everything is the user's own doing. A degraded scan is our problem and
                      must never be dressed up as good news — the banner above already says so, so
                      this stays neutral. Only a clean, complete scan earns "All Clear".
                    */}
                    {filtersActive ? (
                      <div className="py-16 text-center">
                        <div className="w-14 h-14 bg-[rgba(0,0,0,0.04)] border border-[rgba(0,0,0,0.08)] rounded-xl flex items-center justify-center mx-auto mb-3">
                          <Icon icon="mdi:filter-remove-outline" width={26} className="text-[rgba(0,0,0,0.35)]" />
                        </div>
                        <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">
                          No alerts match these filters
                        </h3>
                        <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-4">
                          {alerts.length.toLocaleString()} {alerts.length === 1 ? 'alert is' : 'alerts are'} hidden by the current filters.
                        </p>
                        <button
                          onClick={resetFilters}
                          className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-[13px] font-semibold rounded-lg hover:bg-indigo-700 transition-all active:scale-95"
                        >
                          <Icon icon="mdi:close-circle-outline" width={14} />
                          Clear filters
                        </button>
                      </div>
                    ) : degraded ? (
                      <div className="py-16 text-center">
                        <div className="w-14 h-14 bg-amber-50 border border-amber-100 rounded-xl flex items-center justify-center mx-auto mb-3">
                          <Icon icon="mdi:help-circle-outline" width={28} className="text-amber-500" />
                        </div>
                        <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">
                          No results to show
                        </h3>
                        <p className="text-[13px] text-[rgba(0,0,0,0.45)]">
                          The scan did not complete, so this is not a clean bill of health.
                        </p>
                      </div>
                    ) : (
                      <div className="py-16 text-center">
                        <div className="w-14 h-14 bg-emerald-50 border border-emerald-100 rounded-xl flex items-center justify-center mx-auto mb-3">
                          <Icon icon="mdi:check-circle-outline" width={28} className="text-emerald-500" />
                        </div>
                        <h3 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">All Clear</h3>
                        <p className="text-[13px] text-[rgba(0,0,0,0.45)] mb-4">
                          No critical or high-severity alerts for this project.
                        </p>
                        <button
                          onClick={() => loadAlerts(true)}
                          disabled={isRefreshing}
                          className="inline-flex items-center gap-2 px-4 py-2 bg-indigo-600 text-white text-[13px] font-semibold rounded-lg hover:bg-indigo-700 transition-all active:scale-95 disabled:opacity-50"
                        >
                          <Icon icon="mdi:refresh" width={14} className={isRefreshing ? 'animate-spin' : ''} />
                          Re-check now
                        </button>
                      </div>
                    )}
                  </motion.div>
                )}

                {showTable && (
                  <motion.div key="table" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -4 }} transition={{ duration: 0.28, ease: [0.22, 1, 0.36, 1] }}>
                    <GcpAlertsTable
                      alerts={pagedAlerts}
                      projectId={selectedAccountId}
                      investigations={investigations}
                      investigationsResolved={investigationsResolved}
                      getUsagePercentage={getUsagePercentage}
                      getUsageColor={getUsageColor}
                      getUsageBadgeClass={getUsageBadgeClass}
                      getSecurityBadgeClass={getSecurityBadgeClass}
                      getServiceIconClass={getServiceIconClass}
                      getGcpConsoleLink={getGcpConsoleLink}
                      getGcpActionText={getGcpActionText}
                    />
                    {pageCount > 1 && (
                      <GcpAlertsPagination
                        page={page}
                        pageCount={pageCount}
                        pageSize={pageSize}
                        setPage={setPage}
                        setPageSize={setPageSize}
                        totalResults={filteredAlerts.length}
                      />
                    )}
                  </motion.div>
                )}
              </AnimatePresence>
            </div>
          </>
        )}
      </motion.div>
    </div>
  );
};

export default GcpAlertsPage;
