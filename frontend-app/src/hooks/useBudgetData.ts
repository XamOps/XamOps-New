import { useState, useCallback } from 'react';
import { apiFetchJson } from '../utils/apiClient';
import type {
  BudgetSummary,
  BudgetConfig,
  BudgetCreateRequest,
  BudgetUpdateRequest,
  DailySpendEntry,
} from '../types/aiops';

export interface UseBudgetDataReturn {
  summary: BudgetSummary | null;
  summaryLoading: boolean;
  loadSummary: () => Promise<void>;

  createBudget: (request: BudgetCreateRequest) => Promise<BudgetConfig>;
  updateBudget: (id: number, request: BudgetUpdateRequest) => Promise<BudgetConfig>;
  deleteBudget: (id: number) => Promise<void>;
  toggleBudgetStatus: (id: number, newStatus: string) => Promise<void>;

  dismissAlert: (id: number) => Promise<void>;

  dailySpend: Record<number, DailySpendEntry[]>;
  loadDailySpend: (budgetId: number) => Promise<void>;

  error: string | null;
}

export function useBudgetData(): UseBudgetDataReturn {
  const [summary, setSummary] = useState<BudgetSummary | null>(null);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [dailySpend, setDailySpend] = useState<Record<number, DailySpendEntry[]>>({});

  const BASE = '/api/xamops/aiops/budgets';

  const loadSummary = useCallback(async () => {
    setSummaryLoading(true);
    setError(null);
    try {
      const data = await apiFetchJson<BudgetSummary>(`${BASE}/summary`);
      setSummary(data);
    } catch (e: any) {
      setError(e.message || 'Failed to load budget summary');
    } finally {
      setSummaryLoading(false);
    }
  }, []);

  const createBudget = useCallback(async (request: BudgetCreateRequest): Promise<BudgetConfig> => {
    const result = await apiFetchJson<BudgetConfig>(BASE, {
      method: 'POST',
      body: JSON.stringify(request),
    });
    await loadSummary();
    return result;
  }, [loadSummary]);

  const updateBudget = useCallback(async (id: number, request: BudgetUpdateRequest): Promise<BudgetConfig> => {
    const result = await apiFetchJson<BudgetConfig>(`${BASE}/${id}`, {
      method: 'PUT',
      body: JSON.stringify(request),
    });
    await loadSummary();
    return result;
  }, [loadSummary]);

  const deleteBudget = useCallback(async (id: number): Promise<void> => {
    await apiFetchJson<void>(`${BASE}/${id}`, { method: 'DELETE' });
    await loadSummary();
  }, [loadSummary]);

  const toggleBudgetStatus = useCallback(async (id: number, newStatus: string): Promise<void> => {
    await apiFetchJson<BudgetConfig>(`${BASE}/${id}`, {
      method: 'PUT',
      body: JSON.stringify({ status: newStatus }),
    });
    await loadSummary();
  }, [loadSummary]);

  const dismissAlert = useCallback(async (id: number): Promise<void> => {
    await apiFetchJson<void>(`${BASE}/alerts/${id}/dismiss`, { method: 'POST' });
    await loadSummary();
  }, [loadSummary]);

  const loadDailySpend = useCallback(async (budgetId: number): Promise<void> => {
    try {
      const data = await apiFetchJson<DailySpendEntry[]>(`${BASE}/${budgetId}/daily-spend`);
      setDailySpend(prev => ({ ...prev, [budgetId]: data }));
    } catch (e: any) {
      console.error('Failed to load daily spend:', e);
    }
  }, []);

  return {
    summary, summaryLoading, loadSummary,
    createBudget, updateBudget, deleteBudget, toggleBudgetStatus,
    dismissAlert,
    dailySpend, loadDailySpend,
    error,
  };
}
