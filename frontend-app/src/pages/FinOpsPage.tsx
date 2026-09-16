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

/**
 * True when the picker is showing something other than the default month-to-date view.
 *
 * <p>The page opens on 1st-of-month to today, which is a range but is not a *choice*.
 * Treating it as one would hide most of the page on first load, so the range-scoped
 * behaviour keys off a deliberate selection rather than the mere presence of dates.
 */
const isCustomRange = (filter: DateFilter): boolean => {
  const initial = getInitialDates();
  return filter.startDate !== initial.startDate || filter.endDate !== initial.endDate;
};

const formatRangeLabel = (filter: DateFilter): string => {
  const fmt = (iso: string) =>
    new Date(`${iso}T00:00:00`).toLocaleDateString('en-US', { day: 'numeric', month: 'short', year: 'numeric' });
  return `${fmt(filter.startDate)} – ${fmt(filter.endDate)}`;
};

/** What the Cost and Usage Report can tell us about the selected range. */
interface CurCoverage {
  covered: boolean;
  reason?: string;
  message?: string | null;
  earliestBillingPeriod?: string | null;
}

/**
 * Shown in place of the chart when the report cannot answer the selected range.
 *
 * <p>States the limit and the reason, then offers Cost Explorer as a deliberate choice
 * rather than substituting it silently. The warning is not boilerplate: Cost Explorer
 * genuinely may not have the data either — after Billing Conductor is applied it stops
 * returning older months — so promising a fix here would be a promise the button cannot
 * always keep.
 */
const CurCoveragePanel: React.FC<{
  rangeLabel: string;
  coverage: CurCoverage | null;
  onUseCostExplorer: () => void;
}> = ({ rangeLabel, coverage, onUseCostExplorer }) => (
  <div className="bg-white border border-[rgba(0,0,0,0.12)] rounded-xl shadow-sm p-10 text-center">
    <div className="w-12 h-12 bg-amber-50 rounded-xl flex items-center justify-center mx-auto mb-4">
      <Icon icon="mdi:database-off-outline" width={24} color="#F4A429" />
    </div>
    <h4 className="text-[15px] font-semibold text-[rgba(0,0,0,0.88)] mb-1">
      No Cost and Usage Report data for {rangeLabel}
    </h4>
    <p className="text-[13px] text-[rgba(0,0,0,0.5)] max-w-md mx-auto">
      {coverage?.message ?? 'The Cost and Usage Report cannot serve this date range.'}
    </p>
    <button
      type="button"
      onClick={onUseCostExplorer}
      className="mt-5 inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-[13px] font-medium
                 bg-[rgba(0,0,0,0.88)] text-white hover:bg-[rgba(0,0,0,0.75)] transition-colors"
    >
      <Icon icon="mdi:chart-line" width={14} />
      Fetch from Cost Explorer
    </button>
    <p className="mt-3 text-[11px] text-[rgba(0,0,0,0.35)]">
      Cost Explorer covers the last 14 months, and may also return nothing for this period
      if Billing Conductor has been applied to the account.
    </p>
  </div>
);

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

  // The range the sections are currently loaded for. Applied on "Apply" rather than on
  // every keystroke in the picker, so half-typed dates never trigger a fetch.
  const [appliedFilter, setAppliedFilter] = useState<DateFilter>(dateFilter);
  const rangeScoped = isCustomRange(appliedFilter);

  const [coverage, setCoverage] = useState<CurCoverage | null>(null);
  const [checkingCoverage, setCheckingCoverage] = useState(false);
  /**
   * Set when the user accepts Cost Explorer for the range they are looking at. Reset on
   * every new range on purpose: the report is the better source when it has the data, so
   * opting out of it once should not quietly opt out of it for everything after.
   */
  const [ceApprovedFor, setCeApprovedFor] = useState<string | null>(null);

  const appliedRangeKey = `${appliedFilter.startDate}:${appliedFilter.endDate}`;
  const usingCe = rangeScoped && ceApprovedFor === appliedRangeKey;
  // Only force a source once a range is in play. The default view keeps the page's
  // existing behaviour, where the backend prefers the report and may fall back.
  const costSource = rangeScoped ? (usingCe ? 'CE' : 'CUR') : null;

  // Blocked when the report cannot serve the range and Cost Explorer has not been
  // accepted — there is nothing to fetch, and an empty chart would imply zero spend.
  const blockedOnCoverage = rangeScoped && coverage !== null && !coverage.covered && !usingCe;

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
    dateFilter: appliedFilter,
    costSource,
    rangeScoped,
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
    // A new range always re-asks the report first, even if Cost Explorer was accepted for
    // the previous one.
    setCeApprovedFor(null);
    setCoverage(null);
    setAppliedFilter(dateFilter);
  };

  const clearDateFilter = () => {
    const initialDates = getInitialDates();
    setDateFilter(initialDates);
    setAppliedFilter(initialDates);
    setCeApprovedFor(null);
    setCoverage(null);
    console.log('[FinOps] Date filter reset to current month');
  };

  /**
   * Ask whether the report covers the applied range before loading anything.
   *
   * <p>Up front rather than inferred from the result, because an empty chart cannot say
   * whether the account spent nothing or the report simply does not reach that far back —
   * and only one of those is worth offering Cost Explorer for. The check reads the source
   * registration, so it runs no Athena query and costs nothing.
   */
  useEffect(() => {
    if (!selectedAccountId || !rangeScoped) {
      setCoverage(null);
      return;
    }
    let cancelled = false;
    setCheckingCoverage(true);
    const params = new URLSearchParams({
      accountId: selectedAccountId,
      startDate: appliedFilter.startDate,
      endDate: appliedFilter.endDate,
    });
    fetch(`/api/xamops/finops/cur-coverage?${params}`, { credentials: 'include' })
      .then(res => (res.ok ? res.json() : { covered: false, reason: 'CHECK_FAILED' }))
      .then((data: CurCoverage) => { if (!cancelled) setCoverage(data); })
      .catch(() => {
        // Offer Cost Explorer rather than stranding the page — the same place a genuine
        // miss would lead.
        if (!cancelled) setCoverage({ covered: false, reason: 'CHECK_FAILED' });
      })
      .finally(() => { if (!cancelled) setCheckingCoverage(false); });
    return () => { cancelled = true; };
  }, [selectedAccountId, rangeScoped, appliedFilter.startDate, appliedFilter.endDate]);

  const useCostExplorerForRange = () => {
    setCeApprovedFor(appliedRangeKey);
    loadReport(false);
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

      {/* Which source the figures on screen came from. Only under a custom range, where it
          is a real choice rather than the page's normal preference. */}
      {selectedAccountId && rangeScoped && !blockedOnCoverage && !checkingCoverage && (
        <div className="flex items-center gap-2 text-[12px] text-[rgba(0,0,0,0.5)]">
          <Icon icon={usingCe ? 'mdi:chart-line' : 'mdi:database-outline'} width={13} />
          <span>
            {usingCe ? 'Cost Explorer' : 'Cost and Usage Report'} · {formatRangeLabel(appliedFilter)}
          </span>
        </div>
      )}

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
          {blockedOnCoverage ? null : sectionLoading.kpis || checkingCoverage ? (
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
              {[...Array(rangeScoped ? 1 : 4)].map((_, i) => (
                <div key={i} className="animate-pulse bg-[rgba(0,0,0,0.04)] h-[110px] w-full rounded-xl" />
              ))}
            </div>
          ) : reportData && (
            <FinOpsKPISection
              kpis={reportData.kpis}
              curAccountId={curAccountId}
              spendOnlyLabel={rangeScoped ? 'Spend' : null}
            />
          )}

          {/* Charts Grid — shows when cost-history + cost-by-region resolve */}
          <RevealSection delay={0.05}>
            {blockedOnCoverage ? (
              <CurCoveragePanel
                rangeLabel={formatRangeLabel(appliedFilter)}
                coverage={coverage}
                onUseCostExplorer={useCostExplorerForRange}
              />
            ) : sectionLoading.charts || checkingCoverage ? (
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

          {/* Current-state sections. Hidden under a custom range: waste, rightsizing and
              tagging compliance describe the account as it is now, not as it was during
              the selected window, and their endpoints ignore the date parameters. Showing
              today's figures beneath a historical chart reads as if they belong to it. */}
          {!rangeScoped && (
            <>
          {/* Tagging Compliance — may be slow (full resource scan); shows its own skeleton */}
          <RevealSection delay={0.05}>
            {sectionLoading.tagging ? (
              <div className="animate-pulse bg-[rgba(0,0,0,0.04)] h-48 w-full rounded-xl" />
            ) : reportData && (
              <FinOpsTaggingCompliance
                taggingCompliance={reportData.taggingCompliance}
                pagination={taggingPagination}
                onPaginationChange={(updates) => updatePagination('tagging', updates)}
                accountId={curAccountId}
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
        </>
      )}

    </main>
  );
};

export default FinOpsPage;
