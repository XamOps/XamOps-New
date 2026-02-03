import { useState, useEffect, useCallback } from 'react';
import {
  AzureFinOpsReportData,
  AzureReportSchedule,
  AzureNewBudget,
  AzureNewSchedule
} from '../types/azureFinops';
import {
  fetchAzureFinOpsReport,
  fetchAzureFinOpsSchedules,
  createAzureBudget,
  deleteAzureBudget,
  createAzureFinOpsSchedule,
  deleteAzureFinOpsSchedule
} from '../api/azureFinopsApi';

export interface UseAzureFinOpsDataResult {
  // Data
  reportData: AzureFinOpsReportData | null;
  schedules: AzureReportSchedule[];

  // Loading states
  isLoading: boolean;
  isRefreshing: boolean;
  isCreatingBudget: boolean;
  isCreatingSchedule: boolean;

  // Error state
  error: string | null;

  // Actions
  loadReport: (forceRefresh?: boolean) => Promise<void>;
  loadSchedules: () => Promise<void>;
  handleCreateBudget: (budget: AzureNewBudget) => Promise<void>;
  handleDeleteBudget: (budgetName: string) => Promise<void>;
  handleCreateSchedule: (schedule: AzureNewSchedule) => Promise<void>;
  handleDeleteSchedule: (scheduleId: number) => Promise<void>;
}

export const useAzureFinOpsData = (subscriptionId: string | null): UseAzureFinOpsDataResult => {
  const [reportData, setReportData] = useState<AzureFinOpsReportData | null>(null);
  const [schedules, setSchedules] = useState<AzureReportSchedule[]>([]);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [isRefreshing, setIsRefreshing] = useState<boolean>(false);
  const [isCreatingBudget, setIsCreatingBudget] = useState<boolean>(false);
  const [isCreatingSchedule, setIsCreatingSchedule] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Load Azure FinOps report data
   */
  const loadReport = useCallback(async (forceRefresh: boolean = false) => {
    if (!subscriptionId) {
      console.log('No subscription ID provided, skipping report load');
      setIsLoading(false);
      return;
    }

    try {
      if (forceRefresh) {
        setIsRefreshing(true);
      } else {
        setIsLoading(true);
      }
      setError(null);

      const data = await fetchAzureFinOpsReport(subscriptionId, forceRefresh);
      setReportData(data);
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to load Azure FinOps report';
      setError(errorMessage);
      console.error('Error loading Azure FinOps report:', err);
    } finally {
      setIsLoading(false);
      setIsRefreshing(false);
    }
  }, [subscriptionId]);

  /**
   * Load Azure FinOps report schedules
   */
  const loadSchedules = useCallback(async () => {
    if (!subscriptionId) {
      console.log('No subscription ID provided, skipping schedules load');
      return;
    }

    try {
      const data = await fetchAzureFinOpsSchedules(subscriptionId);
      setSchedules(data.map(s => ({
        ...s,
        subscriptionName: s.accountName || subscriptionId
      })));
    } catch (err) {
      console.error('Error loading Azure FinOps schedules:', err);
      // Don't set error state for schedules as it's not critical
    }
  }, [subscriptionId]);

  /**
   * Create a new Azure budget
   */
  const handleCreateBudget = useCallback(async (budget: AzureNewBudget) => {
    if (!subscriptionId) {
      throw new Error('No subscription ID provided');
    }

    try {
      setIsCreatingBudget(true);
      setError(null);

      const createdBudget = await createAzureBudget(subscriptionId, budget);

      // Update local state
      setReportData(prev => {
        if (!prev) return prev;
        return {
          ...prev,
          budgets: [...(prev.budgets || []), createdBudget]
        };
      });
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to create budget';
      setError(errorMessage);
      throw err;
    } finally {
      setIsCreatingBudget(false);
    }
  }, [subscriptionId]);

  /**
   * Delete an Azure budget
   */
  const handleDeleteBudget = useCallback(async (budgetName: string) => {
    if (!subscriptionId) {
      throw new Error('No subscription ID provided');
    }

    try {
      setError(null);
      await deleteAzureBudget(subscriptionId, budgetName);

      // Update local state
      setReportData(prev => {
        if (!prev) return prev;
        return {
          ...prev,
          budgets: (prev.budgets || []).filter(b => b.budgetName !== budgetName)
        };
      });
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to delete budget';
      setError(errorMessage);
      throw err;
    }
  }, [subscriptionId]);

  /**
   * Create a new Azure FinOps report schedule
   */
  const handleCreateSchedule = useCallback(async (schedule: AzureNewSchedule) => {
    try {
      setIsCreatingSchedule(true);
      setError(null);

      const createdSchedule = await createAzureFinOpsSchedule(schedule);

      // Update local state
      setSchedules(prev => [...prev, {
        ...createdSchedule,
        subscriptionName: createdSchedule.accountName || subscriptionId || ''
      }]);

      return createdSchedule;
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to create schedule';
      setError(errorMessage);
      throw err;
    } finally {
      setIsCreatingSchedule(false);
    }
  }, [subscriptionId]);

  /**
   * Delete an Azure FinOps report schedule
   */
  const handleDeleteSchedule = useCallback(async (scheduleId: number) => {
    try {
      setError(null);
      await deleteAzureFinOpsSchedule(scheduleId);

      // Update local state
      setSchedules(prev => prev.filter(s => s.id !== scheduleId));
    } catch (err) {
      const errorMessage = err instanceof Error ? err.message : 'Failed to delete schedule';
      setError(errorMessage);
      throw err;
    }
  }, []);

  /**
   * Initial data load
   */
  useEffect(() => {
    if (subscriptionId) {
      loadReport(false);
      loadSchedules();
    } else {
      setIsLoading(false);
    }
  }, [subscriptionId, loadReport, loadSchedules]);

  return {
    reportData,
    schedules,
    isLoading,
    isRefreshing,
    isCreatingBudget,
    isCreatingSchedule,
    error,
    loadReport,
    loadSchedules,
    handleCreateBudget,
    handleDeleteBudget,
    handleCreateSchedule,
    handleDeleteSchedule,
  };
};
