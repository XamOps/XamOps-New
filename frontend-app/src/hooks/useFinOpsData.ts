/**
 * useFinOpsData Hook
 *
 * Fetches each FinOps section independently in parallel.
 * Fast sections (billing-summary, budgets) render in 1-5s.
 * Slow sections (tagging-compliance, wasted-resources, rightsizing) populate
 * progressively as they resolve — the page is never fully blank.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import type {
  FinOpsReportData,
  FinOpsKPIs,
  ReportSchedule,
  DateFilter,
  NewBudget,
  NewSchedule,
  Budget,
  TaggingCompliance,
  RightsizingRecommendation,
  WastedResource,
  CostAnomaly,
} from '../types/finops';

interface UseFinOpsDataOptions {
  selectedAccountId: string | null;
  dateFilter: DateFilter;
  /**
   * Which source the figures must come from, appended as `costSource`.
   *
   * <p>'CUR' means the Cost and Usage Report and nothing else — the backend fails rather
   * than falling back, which is the point: under a custom range the page has told the user
   * where the numbers come from, so quietly substituting Cost Explorer would make that a
   * lie. 'CE' is the explicit opt-in after the report turns out not to cover the range.
   * null leaves the default preference alone.
   */
  costSource?: 'CUR' | 'CE' | null;
  /**
   * Load only what a custom date range can actually answer: the spend total and the Cost
   * Explorer breakdown.
   *
   * <p>The other sections describe current state, not a historical window. Waste,
   * rightsizing, tagging compliance and budgets have no meaning "as of last April", and
   * their endpoints ignore the date parameters anyway — so under a range they are not
   * fetched at all rather than shown with figures that silently belong to today.
   */
  rangeScoped?: boolean;
}

export interface SectionLoadingState {
  kpis: boolean;
  budgets: boolean;
  charts: boolean;
  tagging: boolean;
  insights: boolean;
}

interface UseFinOpsDataReturn {
  reportData: FinOpsReportData | null;
  sectionLoading: SectionLoadingState;
  reportSchedules: ReportSchedule[];
  isLoading: boolean;      // true until billing-summary (critical path) resolves
  isRefreshing: boolean;
  error: Error | null;
  loadReport: (forceRefresh?: boolean) => Promise<void>;
  loadSchedules: () => Promise<void>;
  createBudget: (budget: NewBudget) => Promise<Budget>;
  deleteBudget: (budgetName: string) => Promise<void>;
  createSchedule: (schedule: NewSchedule) => Promise<ReportSchedule>;
  deleteSchedule: (scheduleId: number) => Promise<void>;
}

/** Empty report skeleton — lets sections render as they fill in */
const emptyReport = (): FinOpsReportData => ({
  kpis: { monthToDateSpend: 0, forecastedSpend: 0, lastMonthSpend: 0, potentialSavings: 0 },
  costBreakdown: { byService: [], byRegion: [] },
  budgets: [],
  rightsizingRecommendations: [],
  wastedResources: [],
  costAnomalies: [],
  taggingCompliance: { compliancePercentage: 0, totalResourcesScanned: 0, untaggedResourcesCount: 0, untaggedResources: [] },
});

/** Compute KPI values from billing-summary array */
function computeKpisFromBilling(
  billing: Array<{ serviceName: string; monthToDateCost: number }>,
  lastMonthSpend: number,
  potentialSavings: number
): FinOpsKPIs {
  const mtd = billing.reduce((sum, s) => sum + (s.monthToDateCost ?? 0), 0);
  const today = new Date();
  const daysInMonth = new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate();
  const currentDay = today.getDate();
  const forecasted = currentDay > 0 ? (mtd / currentDay) * daysInMonth : 0;
  return { monthToDateSpend: mtd, forecastedSpend: forecasted, lastMonthSpend, potentialSavings };
}

export const useFinOpsData = ({
  selectedAccountId,
  dateFilter,
  costSource = null,
  rangeScoped = false,
}: UseFinOpsDataOptions): UseFinOpsDataReturn => {
  const [reportData, setReportData] = useState<FinOpsReportData | null>(null);
  const [reportSchedules, setReportSchedules] = useState<ReportSchedule[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);
  const [sectionLoading, setSectionLoading] = useState<SectionLoadingState>({
    kpis: false, budgets: false, charts: false, tagging: false, insights: false,
  });

  // Track in-flight request version so stale responses from old accounts are ignored
  const requestVersion = useRef(0);
  // AbortController to cancel in-flight fetch calls when account/date changes or unmount
  const abortControllerRef = useRef<AbortController | null>(null);

  const loadReport = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        setIsLoading(false);
        return;
      }

      const version = ++requestVersion.current;

      // Cancel any in-flight requests from the previous load
      abortControllerRef.current?.abort();
      abortControllerRef.current = new AbortController();
      const { signal } = abortControllerRef.current;

      if (forceRefresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setError(null);

      // Show empty skeleton immediately so page renders right away
      setReportData(emptyReport());
      setSectionLoading({ kpis: true, budgets: true, charts: true, tagging: true, insights: true });

      const sourceParam = costSource ? `&costSource=${costSource}` : '';
      const base = `accountId=${selectedAccountId}&forceRefresh=${forceRefresh}${sourceParam}`;
      const dateParams = dateFilter.startDate && dateFilter.endDate
        ? `&startDate=${dateFilter.startDate}&endDate=${dateFilter.endDate}`
        : '';

      /** Fetch one section; on success, call `apply` to merge into report state */
      const fetchSection = async <T>(
        path: string,
        apply: (data: T) => Partial<FinOpsReportData>
      ): Promise<T | null> => {
        try {
          const res = await fetch(`/api/xamops/finops/${path}?${base}${dateParams}`, { credentials: 'include', signal });
          if (!res.ok) {
            console.warn(`[FinOps] ${path} returned ${res.status}`);
            return null;
          }
          const data: T = await res.json();
          if (version !== requestVersion.current) return null; // stale response — discard
          setReportData(prev => prev ? { ...prev, ...apply(data) } : null);
          return data;
        } catch (err) {
          if (err instanceof DOMException && err.name === 'AbortError') return null; // cancelled — not an error
          console.warn(`[FinOps] ${path} failed:`, err);
          return null;
        }
      };

      // Track accumulated savings for KPI computation
      let lastMonthSpend = 0;
      let potentialSavings = 0;
      let billingData: Array<{ serviceName: string; monthToDateCost: number }> = [];

      try {
        // ── Fast parallel group 1: billing-summary + budgets + last-month-spend ─
        // last-month-spend is fetched from its own endpoint (previous calendar
        // month total) so it stays consistent with the Account Dashboard and is
        // NOT affected by the Cost Explorer date filter.
        const [billing, lastMonth] = await Promise.all([
          fetchSection<Array<{ serviceName: string; monthToDateCost: number }>>(
            'billing-summary',
            data => ({
              kpis: computeKpisFromBilling(data, 0, 0),
              costBreakdown: {
                byService: data
                  .sort((a, b) => b.monthToDateCost - a.monthToDateCost)
                  .slice(0, 10)
                  .map(s => ({ service: s.serviceName, cost: s.monthToDateCost })),
                byRegion: [],
              },
            })
          ),
          fetchSection<{ lastMonthSpend: number }>('last-month-spend', () => ({})),
          fetchSection<Budget[]>('budgets', data => ({ budgets: data })),
        ]);

        if (lastMonth && typeof lastMonth.lastMonthSpend === 'number') {
          lastMonthSpend = lastMonth.lastMonthSpend;
        }

        if (version !== requestVersion.current) return;
        billingData = billing ?? [];
        // Recompute KPIs now that we have the authoritative last-month spend
        setReportData(prev => prev
          ? { ...prev, kpis: computeKpisFromBilling(billingData, lastMonthSpend, potentialSavings) }
          : null
        );
        setSectionLoading(prev => ({ ...prev, kpis: false, budgets: false }));
        setIsLoading(false);   // critical path done — page is now interactive
        setIsRefreshing(false);

        // ── Slower parallel group 2: cost-history + cost-by-region ───────────
        const [costHistory, costByRegion] = await Promise.all([
          fetchSection<{ labels: string[]; costs: number[]; anomalies: boolean[] }>(
            'cost-history',
            () => ({}) // applied separately below after we extract lastMonthSpend
          ),
          fetchSection<Array<{ region: string; cost: number }>>(
            'cost-by-region',
            () => ({})  // region data applied below using prev-state pattern to avoid clobbering byService
          ),
        ]);

        if (version !== requestVersion.current) return;

        // NOTE: lastMonthSpend comes from the dedicated /last-month-spend endpoint
        // (previous calendar month), NOT from cost-history — cost-history returns
        // daily data when a date range is applied, which would otherwise make
        // costs[length-2] the second-to-last DAY instead of the previous month.
        void costHistory;
        setSectionLoading(prev => ({ ...prev, charts: false }));

        // Apply cost-by-region to breakdown now that we have byService already set
        if (costByRegion) {
          setReportData(prev => prev
            ? {
                ...prev,
                costBreakdown: {
                  byService: prev.costBreakdown?.byService ?? [],
                  byRegion: (costByRegion as Array<{ region?: string; cost: number }>)
                    .map(r => ({ region: r.region, cost: r.cost })),
                },
              }
            : null
          );
        }

        // ── Slowest parallel group 3: tagging + wasted + rightsizing ─────────
        // Skipped under a custom range: these describe current state, and their endpoints
        // do not take the date parameters, so fetching them would put today's waste and
        // rightsizing figures beside a chart showing last April.
        if (rangeScoped) {
          if (version === requestVersion.current) {
            setSectionLoading(prev => ({ ...prev, tagging: false, insights: false }));
          }
          return;
        }

        await Promise.all([
          fetchSection<TaggingCompliance>(
            'tagging-compliance',
            data => ({ taggingCompliance: data })
          ).then(() => {
            if (version === requestVersion.current) {
              setSectionLoading(prev => ({ ...prev, tagging: false }));
            }
          }),

          fetchSection<RightsizingRecommendation[]>(
            'rightsizing',
            data => ({ rightsizingRecommendations: data })
          ).then(data => {
            if (version !== requestVersion.current) return;
            const rightsizingSavings = (data ?? []).reduce((s, r) => s + (r.estimatedMonthlySavings ?? 0), 0);
            potentialSavings += rightsizingSavings;
            setReportData(prev => prev
              ? { ...prev, kpis: computeKpisFromBilling(billingData, lastMonthSpend, potentialSavings) }
              : null
            );
          }),

          fetchSection<WastedResource[]>(
            'wasted-resources',
            data => ({ wastedResources: data })
          ).then(data => {
            if (version !== requestVersion.current) return;
            const wasteSavings = (data ?? []).reduce((s, r) => s + (r.monthlySavings ?? 0), 0);
            potentialSavings += wasteSavings;
            setReportData(prev => prev
              ? { ...prev, kpis: computeKpisFromBilling(billingData, lastMonthSpend, potentialSavings) }
              : null
            );
          }),

          fetchSection<CostAnomaly[]>('cost-anomalies', data => ({ costAnomalies: data })),
        ]);

        if (version !== requestVersion.current) return;
        setSectionLoading(prev => ({ ...prev, insights: false }));

      } catch (err) {
        if (version !== requestVersion.current) return;
        console.error('[FinOps] Failed to load report:', err);
        setError(err instanceof Error ? err : new Error('Failed to load report'));
      } finally {
        if (version === requestVersion.current) {
          setIsLoading(false);
          setIsRefreshing(false);
        }
      }
    },
    [selectedAccountId, dateFilter, costSource, rangeScoped] // eslint-disable-line react-hooks/exhaustive-deps
  );

  // Load schedules
  const loadSchedules = useCallback(async () => {
    if (!selectedAccountId) return;
    try {
      const response = await fetch(`/api/xamops/finops-schedules?accountId=${selectedAccountId}`, {
        credentials: 'include',
      });
      if (response.ok) {
        setReportSchedules(await response.json());
      }
    } catch (err) {
      console.error('Failed to load schedules:', err);
    }
  }, [selectedAccountId]);

  // Create budget
  const createBudget = useCallback(
    async (budget: NewBudget): Promise<Budget> => {
      if (!selectedAccountId) throw new Error('No account selected');
      const response = await fetch(`/api/xamops/finops/budgets?accountId=${selectedAccountId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(budget),
      });
      if (!response.ok) throw new Error('Failed to create budget');
      const created = await response.json();
      setReportData(prev => prev ? { ...prev, budgets: [...(prev.budgets ?? []), created] } : prev);
      return created;
    },
    [selectedAccountId]
  );

  // Delete budget
  const deleteBudget = useCallback(
    async (budgetName: string): Promise<void> => {
      if (!selectedAccountId) throw new Error('No account selected');
      const response = await fetch(
        `/api/xamops/finops/budgets?accountId=${selectedAccountId}&budgetName=${encodeURIComponent(budgetName)}`,
        { method: 'DELETE', credentials: 'include' }
      );
      if (!response.ok) throw new Error('Failed to delete budget');
      setReportData(prev =>
        prev ? { ...prev, budgets: prev.budgets.filter(b => b.budgetName !== budgetName) } : prev
      );
    },
    [selectedAccountId]
  );

  // Create schedule
  const createSchedule = useCallback(async (schedule: NewSchedule): Promise<ReportSchedule> => {
    const response = await fetch('/api/xamops/finops-schedules', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      credentials: 'include',
      body: JSON.stringify(schedule),
    });
    if (!response.ok) throw new Error('Failed to create schedule. Check console.');
    const created = await response.json();
    setReportSchedules(prev => [...prev, created]);
    return created;
  }, []);

  // Delete schedule
  const deleteSchedule = useCallback(async (scheduleId: number): Promise<void> => {
    const response = await fetch(`/api/xamops/finops-schedules/${scheduleId}`, {
      method: 'DELETE',
      credentials: 'include',
    });
    if (!response.ok) throw new Error('Failed to delete schedule');
    setReportSchedules(prev => prev.filter(s => s.id !== scheduleId));
  }, []);

  // Initial load
  useEffect(() => {
    if (selectedAccountId) {
      loadReport(false);
      loadSchedules();
    }
  }, [selectedAccountId, loadReport, loadSchedules]);

  // Cancel in-flight requests on unmount
  useEffect(() => {
    return () => { abortControllerRef.current?.abort(); };
  }, []);

  return {
    reportData,
    sectionLoading,
    reportSchedules,
    isLoading,
    isRefreshing,
    error,
    loadReport,
    loadSchedules,
    createBudget,
    deleteBudget,
    createSchedule,
    deleteSchedule,
  };
};
