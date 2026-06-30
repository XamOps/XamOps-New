/**
 * useGcpBudgetData Hook
 *
 * Manages GCP budget CRUD operations for the GCP Cost page.
 * Mirrors useFinOpsBudgetData but hits GCP-specific endpoints.
 */

import { useState, useEffect, useCallback } from 'react';
import type { Budget, NewBudget } from '../types/finops';

interface UseGcpBudgetDataOptions {
  selectedAccountId: string | null;
}

interface UseGcpBudgetDataReturn {
  budgets: Budget[];
  isLoading: boolean;
  loadBudgets: () => Promise<void>;
  createBudget: (budget: NewBudget) => Promise<Budget>;
  deleteBudget: (budgetName: string) => Promise<void>;
}

export const useGcpBudgetData = ({
  selectedAccountId: rawAccountId,
}: UseGcpBudgetDataOptions): UseGcpBudgetDataReturn => {
  const selectedAccountId = rawAccountId?.split(',')[0] || null;
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  // Load budgets
  const loadBudgets = useCallback(async () => {
    if (!selectedAccountId) return;
    setIsLoading(true);
    try {
      const response = await fetch(
        `/api/xamops/gcp/finops/budgets?accountId=${selectedAccountId}`,
        { credentials: 'include' }
      );
      if (response.ok) {
        const data = await response.json();
        // Map GCP budget shape to common Budget type
        const mapped: Budget[] = (Array.isArray(data) ? data : []).map((b: any) => ({
          budgetName: b.budgetName,
          budgetLimit: b.budgetLimit,
          budgetUnit: 'USD',
          actualSpend: b.actualSpend || 0,
          forecastedSpend: b.forecastedSpend || 0,
          serviceName: b.serviceName || null,
        }));
        setBudgets(mapped);
      }
    } catch (err) {
      console.error('[GcpBudget] Failed to load budgets:', err);
    } finally {
      setIsLoading(false);
    }
  }, [selectedAccountId]);

  // Create budget
  const createBudget = useCallback(
    async (budget: NewBudget): Promise<Budget> => {
      if (!selectedAccountId) throw new Error('No account selected');
      const response = await fetch(`/api/xamops/gcp/finops/budgets?accountId=${selectedAccountId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          budgetName: budget.budgetName,
          budgetLimit: budget.budgetLimit,
          currencyCode: budget.budgetUnit || 'USD',
        }),
      });
      if (!response.ok) {
        const errorText = await response.text().catch(() => '');
        throw new Error(errorText || `Failed to create budget (${response.status})`);
      }
      const created = await response.json();
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
        `/api/xamops/gcp/finops/budgets?accountId=${selectedAccountId}&budgetName=${encodeURIComponent(budgetName)}`,
        { method: 'DELETE', credentials: 'include' }
      );
      if (!response.ok) throw new Error('Failed to delete budget');
      await loadBudgets();
    },
    [selectedAccountId, loadBudgets]
  );

  // Initial load
  useEffect(() => {
    if (selectedAccountId) {
      loadBudgets();
    }
  }, [selectedAccountId, loadBudgets]);

  return {
    budgets,
    isLoading,
    loadBudgets,
    createBudget,
    deleteBudget,
  };
};
