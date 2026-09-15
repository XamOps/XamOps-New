/**
 * useAzureBudgetData Hook
 *
 * Manages Azure budget CRUD operations for the Azure Cost page.
 * Mirrors useFinOpsBudgetData but hits Azure-specific endpoints.
 */

import { useState, useEffect, useCallback } from 'react';
import type { Budget, NewBudget } from '../types/finops';

interface UseAzureBudgetDataOptions {
  selectedAccountId: string | null;
}

interface UseAzureBudgetDataReturn {
  budgets: Budget[];
  isLoading: boolean;
  /**
   * Set when budgets could not be read, as opposed to there being none.
   *
   * Carries the remediation Azure's failure implies — usually a missing 'Cost Management Reader'
   * assignment, or a subscription offer the Consumption API does not serve.
   */
  error: string | null;
  loadBudgets: () => Promise<void>;
  createBudget: (budget: NewBudget) => Promise<Budget>;
  deleteBudget: (budgetName: string) => Promise<void>;
}

/**
 * Pulls the server's own message out of an error response.
 *
 * The Azure budget endpoints return `{"error": "..."}` with text that names the remediation, e.g.
 * which role to assign. Showing the raw body put JSON in front of the user; showing only the status
 * threw the message away.
 */
const readErrorMessage = async (response: Response, fallback: string): Promise<string> => {
  try {
    const body = await response.clone().json();
    if (body?.error) return body.error;
  } catch {
    // Not JSON — fall through to the text body.
  }
  const text = await response.text().catch(() => '');
  return text || `${fallback} (${response.status})`;
};

export const useAzureBudgetData = ({
  selectedAccountId: rawAccountId,
}: UseAzureBudgetDataOptions): UseAzureBudgetDataReturn => {
  const selectedAccountId = rawAccountId?.split(',')[0] || null;
  const [budgets, setBudgets] = useState<Budget[]>([]);
  const [isLoading, setIsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load budgets
  const loadBudgets = useCallback(async () => {
    if (!selectedAccountId) return;
    setIsLoading(true);
    setError(null);
    try {
      const response = await fetch(
        `/api/azure/finops/budgets?subscriptionId=${selectedAccountId}`,
        { credentials: 'include' }
      );
      if (response.ok) {
        const data = await response.json();
        // Map Azure budget shape to common Budget type
        const mapped: Budget[] = (Array.isArray(data) ? data : []).map((b: any) => ({
          budgetName: b.budgetName,
          budgetLimit: b.budgetLimit,
          budgetUnit: b.budgetUnit || 'USD',
          actualSpend: b.actualSpend || 0,
          forecastedSpend: b.forecastedSpend || 0,
          serviceName: b.serviceName || null,
        }));
        setBudgets(mapped);
      } else {
        // The endpoint now distinguishes "could not read budgets" (403/503 with a reason) from
        // "there are none" (200 with an empty array). Swallowing the first left the page showing
        // "No Budgets Found" when the actual problem was a missing role assignment.
        let reason = `Budgets could not be read (HTTP ${response.status}).`;
        try {
          const body = await response.json();
          if (body?.error) reason = body.error;
        } catch {
          // Non-JSON error body; keep the status-based message.
        }
        console.warn('[AzureBudget] Budgets unavailable:', reason);
        setBudgets([]);
        setError(reason);
      }
    } catch (err) {
      console.error('[AzureBudget] Failed to load budgets:', err);
      setBudgets([]);
      setError('Budgets could not be loaded. Check your connection and try again.');
    } finally {
      setIsLoading(false);
    }
  }, [selectedAccountId]);

  // Create budget
  const createBudget = useCallback(
    async (budget: NewBudget): Promise<Budget> => {
      if (!selectedAccountId) throw new Error('No account selected');
      const response = await fetch(`/api/azure/finops/budgets?subscriptionId=${selectedAccountId}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify({
          budgetName: budget.budgetName,
          budgetLimit: budget.budgetLimit,
          budgetUnit: budget.budgetUnit || 'USD',
        }),
      });
      if (!response.ok) {
        // The controller now returns {"error": "..."} with an actionable message — a rejected name or
        // limit as 400, a missing role or unsupported offer as 403. Raising the raw JSON put
        // `{"error":"..."}` in front of the user.
        throw new Error(await readErrorMessage(response, 'Failed to create budget'));
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
        `/api/azure/finops/budgets?subscriptionId=${selectedAccountId}&budgetName=${encodeURIComponent(budgetName)}`,
        { method: 'DELETE', credentials: 'include' }
      );
      if (!response.ok) {
        throw new Error(await readErrorMessage(response, 'Failed to delete budget'));
      }
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
    error,
    loadBudgets,
    createBudget,
    deleteBudget,
  };
};
