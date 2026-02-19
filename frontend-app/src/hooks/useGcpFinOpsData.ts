/**
 * useGcpFinOpsData Hook
 *
 * Custom hook to fetch and manage GCP FinOps report data
 */

import { useState, useEffect, useCallback } from 'react';
import type {
  GcpFinOpsReportData,
  GcpReportSchedule,
  DateFilter,
  GcpNewBudget,
  GcpNewSchedule,
  GcpBudget,
} from '../types/gcpFinops';

interface UseGcpFinOpsDataOptions {
  selectedAccountId: string | null;
  dateFilter: DateFilter;
}

interface UseGcpFinOpsDataReturn {
  reportData: GcpFinOpsReportData | null;
  reportSchedules: GcpReportSchedule[];
  isLoading: boolean;
  isRefreshing: boolean;
  error: Error | null;
  loadReport: (forceRefresh?: boolean) => Promise<void>;
  loadSchedules: () => Promise<void>;
  createBudget: (budget: GcpNewBudget) => Promise<GcpBudget>;
  deleteBudget: (budgetName: string) => Promise<void>;
  createSchedule: (schedule: GcpNewSchedule) => Promise<GcpReportSchedule>;
  deleteSchedule: (scheduleId: number) => Promise<void>;
}

export const useGcpFinOpsData = ({
  selectedAccountId,
  dateFilter,
}: UseGcpFinOpsDataOptions): UseGcpFinOpsDataReturn => {
  const [reportData, setReportData] = useState<GcpFinOpsReportData | null>(null);
  const [reportSchedules, setReportSchedules] = useState<GcpReportSchedule[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [error, setError] = useState<Error | null>(null);


  // Load report data
  const loadReport = useCallback(
    async (forceRefresh: boolean = false) => {
      if (!selectedAccountId) {
        console.log('[GCP FinOps] No account selected, skipping report load.');
        setIsLoading(false);
        return;
      }

      if (forceRefresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }

      try {
        let url = `/api/xamops/gcp/finops/report?accountId=${selectedAccountId}&forceRefresh=${forceRefresh}`;

        // Add date range parameters if both dates are set
        if (dateFilter.startDate && dateFilter.endDate) {
          url += `&startDate=${dateFilter.startDate}&endDate=${dateFilter.endDate}`;
        }

        console.log('[GCP FinOps] Fetching report for account:', selectedAccountId, 'forceRefresh:', forceRefresh);

        const response = await fetch(url, {
          credentials: 'include'
        });

        if (!response.ok) {
          const text = await response.text();
          throw new Error(`HTTP error! status: ${response.status}, body: ${text}`);
        }

        const data = await response.json();

        if (data) {
          setReportData(data);
          console.log('[GCP FinOps] Report data loaded successfully', data);
        } else {
          console.warn('[GCP FinOps] Received empty data');
        }
      } catch (err) {
        console.error('[GCP FinOps] Failed to load GCP FinOps report:', err);
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
      const response = await fetch(`/api/xamops/gcp/finops-schedules?accountId=${selectedAccountId}`, {
        credentials: 'include',
      });

      if (response.ok) {
        const data = await response.json();
        setReportSchedules(data);
      }
    } catch (err) {
      console.error('[GCP FinOps] Failed to load schedules:', err);
    }
  }, [selectedAccountId]);

  // Create budget
  const createBudget = useCallback(
    async (budget: GcpNewBudget): Promise<GcpBudget> => {
      if (!selectedAccountId) {
        throw new Error('No account selected');
      }

      const response = await fetch(`/api/xamops/gcp/finops/budgets?accountId=${selectedAccountId}`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
        },
        credentials: 'include',
        body: JSON.stringify(budget),
      });

      if (!response.ok) {
        const errorBody = await response.json().catch(() => ({}));
        throw new Error((errorBody as { error?: string }).error || 'Failed to create budget');
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
        `/api/xamops/gcp/finops/budgets?accountId=${selectedAccountId}&budgetName=${encodeURIComponent(budgetName)}`,
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
  const createSchedule = useCallback(async (schedule: GcpNewSchedule): Promise<GcpReportSchedule> => {
    const response = await fetch('/api/xamops/gcp/finops-schedules', {
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
    const response = await fetch(`/api/xamops/gcp/finops-schedules/${scheduleId}`, {
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
    } else {
      setReportData(null);
      setIsLoading(false);
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
