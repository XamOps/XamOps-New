/**
 * useFinOpsData Hook
 *
 * Custom hook to fetch and manage FinOps report data
 */

import { useState, useEffect, useCallback } from 'react';
import type {
  FinOpsReportData,
  ReportSchedule,
  DateFilter,
  NewBudget,
  NewSchedule,
  Budget,
} from '../types/finops';

interface UseFinOpsDataOptions {
  selectedAccountId: string | null;
  dateFilter: DateFilter;
}

interface UseFinOpsDataReturn {
  reportData: FinOpsReportData | null;
  reportSchedules: ReportSchedule[];
  isLoading: boolean;
  isRefreshing: boolean;
  error: Error | null;
  loadReport: (forceRefresh?: boolean) => Promise<void>;
  loadSchedules: () => Promise<void>;
  createBudget: (budget: NewBudget) => Promise<Budget>;
  deleteBudget: (budgetName: string) => Promise<void>;
  createSchedule: (schedule: NewSchedule) => Promise<ReportSchedule>;
  deleteSchedule: (scheduleId: number) => Promise<void>;
}

export const useFinOpsData = ({
  selectedAccountId,
  dateFilter,
}: UseFinOpsDataOptions): UseFinOpsDataReturn => {
  const [reportData, setReportData] = useState<FinOpsReportData | null>(null);
  const [reportSchedules, setReportSchedules] = useState<ReportSchedule[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);

  // Load report data
  const loadReport = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        console.log('[FinOps] No account selected, skipping report load.');
        setIsLoading(false);
        return;
      }

      if (forceRefresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }

      try {
        let url = `/api/xamops/finops/report?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;

        // Add date range parameters if both dates are set
        if (dateFilter.startDate && dateFilter.endDate) {
          url += `&startDate=${dateFilter.startDate}&endDate=${dateFilter.endDate}`;
          console.log(
            '[FinOps] Fetching report with date range:',
            dateFilter.startDate,
            'to',
            dateFilter.endDate
          );
        }

        console.log(`[FinOps] 🚀 Fetching report for account ${selectedAccountId}...`, {
          url,
          forceRefresh,
          startDate: dateFilter?.startDate,
          endDate: dateFilter?.endDate
        });

        const response = await fetch(url, {
          credentials: 'include',
        });
        console.log(`[FinOps] 📥 Response received (Status: ${response.status} ${response.statusText})`);

        if (!response.ok) {
          let errorMsg = `HTTP error! status: ${response.status}`;
          let errorData = null;
          try {
            const text = await response.clone().text();
            console.log(`[FinOps] 📄 Error response body:`, text);
            errorData = JSON.parse(text);
            errorMsg = errorData.error || errorData.message || errorMsg;
          } catch (e) {
            console.warn('[FinOps] ⚠️ Could not parse error response as JSON', e);
          }

          const error = new Error(errorMsg);
          console.error('[FinOps] ❌ Request failed:', {
            status: response.status,
            message: errorMsg,
            data: errorData
          });
          throw error;
        }

        // Check for 204 No Content which should be handled before .json()
        if (response.status === 204) {
          console.warn('[FinOps] ⚠️ Backend returned 204 No Content');
          setReportData(null);
          return;
        }

        try {
          const data = await response.json();
          console.log('[FinOps] ✅ Data parsed successfully:', data);
          setReportData(data);
        } catch (e) {
          console.error('[FinOps] ❌ JSON Parsing Error:', e);
          throw new Error('Failed to parse FinOps report data. Please check console for details.');
        }
      } catch (err) {
        console.error('[FinOps] Failed to load FinOps report:', err);
        setError(err instanceof Error ? err : new Error('Failed to load report'));
      } finally {
        setIsLoading(false);
        setIsRefreshing(false);
      }
    },
    [selectedAccountId, dateFilter]
  );

  // Load schedules
  const loadSchedules = useCallback(async () => {
    if (!selectedAccountId) return;

    try {
      const response = await fetch(`/api/xamops/finops-schedules?accountId=${selectedAccountId}`, {
        credentials: 'include',
      });

      if (response.ok) {
        const data = await response.json();
        setReportSchedules(data);
      }
    } catch (err) {
      console.error('Failed to load schedules:', err);
    }
  }, [selectedAccountId]);

  // Create budget
  const createBudget = useCallback(
    async (budget: NewBudget): Promise<Budget> => {
      if (!selectedAccountId) {
        throw new Error('No account selected');
      }

      const response = await fetch(`/api/xamops/finops/budgets?accountId=${selectedAccountId}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
        body: JSON.stringify(budget),
      });

      if (!response.ok) {
        throw new Error('Failed to create budget');
      }

      const createdBudget = await response.json();

      // Update local state
      setReportData((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          budgets: [...(prev.budgets || []), createdBudget],
        };
      });

      return createdBudget;
    },
    [selectedAccountId]
  );

  // Delete budget
  const deleteBudget = useCallback(
    async (budgetName: string): Promise<void> => {
      if (!selectedAccountId) {
        throw new Error('No account selected');
      }

      const response = await fetch(
        `/api/xamops/finops/budgets?accountId=${selectedAccountId}&budgetName=${encodeURIComponent(budgetName)}`,
        {
          method: 'DELETE',
          credentials: 'include',
        }
      );

      if (!response.ok) {
        throw new Error('Failed to delete budget');
      }

      // Update local state
      setReportData((prev) => {
        if (!prev) return prev;
        return {
          ...prev,
          budgets: prev.budgets.filter((b) => b.budgetName !== budgetName),
        };
      });
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

    if (!response.ok) {
      throw new Error('Failed to create schedule. Check console.');
    }

    const createdSchedule = await response.json();

    // Update local state
    setReportSchedules((prev) => [...prev, createdSchedule]);

    return createdSchedule;
  }, []);

  // Delete schedule
  const deleteSchedule = useCallback(async (scheduleId: number): Promise<void> => {
    const response = await fetch(`/api/xamops/finops-schedules/${scheduleId}`, {
      method: 'DELETE',
      credentials: 'include',
    });

    if (!response.ok) {
      throw new Error('Failed to delete schedule');
    }

    // Update local state
    setReportSchedules((prev) => prev.filter((s) => s.id !== scheduleId));
  }, []);

  // Initial load
  useEffect(() => {
    if (selectedAccountId) {
      loadReport(false);
      loadSchedules();
    }
  }, [selectedAccountId, loadReport, loadSchedules]);

  return {
    reportData,
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
