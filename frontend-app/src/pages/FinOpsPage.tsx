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
import type { DateFilter, PaginationState } from '../types/finops';
import { Icon } from '@iconify/react';

// Component imports
import FinOpsHeader from '../components/FinOps/FinOpsHeader';
import FinOpsKPISection from '../components/FinOps/FinOpsKPISection';
import FinOpsCharts from '../components/FinOps/FinOpsCharts';
import FinOpsTaggingCompliance from '../components/FinOps/FinOpsTaggingCompliance';
import FinOpsInsights from '../components/FinOps/FinOpsInsights';

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
  const { selectedAccountIds, accounts } = useAccounts();
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;

  /**
   * Account the CUR reload applies to, or null to hide the button. A CUR belongs to a
   * single AWS account, so a multi-account selection or a GCP/Azure account has nothing
   * to reload — hiding beats offering a button that can only error.
   */
  const curAccountId = React.useMemo(() => {
    if (selectedAccountIds.length !== 1) return null;
    const account = accounts.find(a => a.id === selectedAccountIds[0]);
    return (account?.provider ?? 'AWS') === 'AWS' ? selectedAccountIds[0] : null;
  }, [selectedAccountIds, accounts]);


  // Date filter state - initialized to match Cost page defaults
  const [dateFilter, setDateFilter] = useState<DateFilter>(getInitialDates());

  // Get FinOps data
  const {
    reportData,
    sectionLoading,
    isLoading,
    isRefreshing,
    error,
    loadReport,
  } = useFinOpsData({
    selectedAccountId,
    dateFilter,
  });

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
        curAccountId={curAccountId}
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

    </main>
  );
};

export default FinOpsPage;
