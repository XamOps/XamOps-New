/**
 * useFinOpsBudgetData Hook
 *
 * Manages FinOps budget and schedule CRUD operations for the Cost page.
 * Extracted from useFinOpsData to be used independently.
 */

import { useState, useEffect, useCallback } from 'react';
import type { Budget, NewBudget, ReportSchedule, NewSchedule } from '../types/finops';

interface UseFinOpsBudgetDataOptions {
  selectedAccountId: string | null;
}

interface UseFinOpsBudgetDataReturn {
  budgets: Budget[];
  reportSchedules: ReportSchedule[];
  isLoading: boolean;
  loadBudgets: () => Promise<void>;
  loadSchedules: () => Promise<void>;
  createBudget: (budget: NewBudget) => Promise<Budget>;
  deleteBudget: (budgetName: string) => Promise<void>;
  createSchedule: (schedule: NewSchedule) => Promise<ReportSchedule>;
  deleteSchedule: (scheduleId: number) => Promise<void>;
}

export const useFinOpsBudgetData = ({
  selectedAccountId: rawAccountId,
}: UseFinOpsBudgetDataOptions): UseFinOpsBudgetDataReturn => {
  // Budget API works with a single account — use first if comma-separated
  const selectedAccountId = rawAccountId?.split(',')[0] || null;
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [reportSchedules, setReportSchedules] = useState<ReportSchedule[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  // Load budgets
  const loadBudgets = useCallback(async () => {
    if (!selectedAccountId) return;
    setIsLoading(true);
    try {
      const response = await fetch(
        `/api/xamops/finops/budgets?accountId=${selectedAccountId}&forceRefresh=true`,
        { credentials: 'include' }
      );
      if (response.ok) {
        const data = await response.json();
        setBudgets(Array.isArray(data) ? data : []);
      }
    } catch (err) {
      console.error('[Budget] Failed to load budgets:', err);
    } finally {
      setIsLoading(false);
    }
  }, [selectedAccountId]);

  // Load schedules
  const loadSchedules = useCallback(async () => {
    if (!selectedAccountId) return;
    try {
      const response = await fetch(
        `/api/xamops/finops-schedules?accountId=${selectedAccountId}`,
        { credentials: 'include' }
      );
      if (response.ok) {
        setReportSchedules(await response.json());
      }
    } catch (err) {
      console.error('[Budget] Failed to load schedules:', err);
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
        body: JSON.stringify({
          budgetName: budget.budgetName,
          budgetLimit: budget.budgetLimit,
          budgetUnit: budget.budgetUnit || 'USD',
          serviceName: budget.serviceName || null,
        }),
      });
      if (!response.ok) {
        const errorText = await response.text().catch(() => '');
        console.error('[Budget] Create failed:', response.status, errorText);
        throw new Error(errorText || `Failed to create budget (${response.status})`);
      }
      const created = await response.json();
      // Reload full budget list from backend to get fresh data
      await loadBudgets();
      return created;
    },
    [selectedAccountId, loadBudgets]
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
      setBudgets(prev => prev.filter(b => b.budgetName !== budgetName));
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
      loadBudgets();
      loadSchedules();
    }
  }, [selectedAccountId, loadBudgets, loadSchedules]);

  return {
    budgets,
    reportSchedules,
    isLoading,
    loadBudgets,
    loadSchedules,
    createBudget,
    deleteBudget,
    createSchedule,
    deleteSchedule,
  };
};
