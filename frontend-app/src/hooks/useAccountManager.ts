/**
 * useAccountManager Hook
 *
 * Custom hook for managing Account Manager page state and API calls
 * Converted from: account-manager.html (Alpine.js accountManager() function)
 */

import { useState, useEffect, useCallback } from 'react';
import apiClient, { apiFetch } from '../utils/apiClient';
import type { CloudAccount, GitHubConfig, JenkinsConfig, VerificationDetails } from '../types/accountManager';

interface UseAccountManagerReturn {
  // State
  accounts: CloudAccount[];
  githubConfigs: GitHubConfig[];
  jenkinsConfigs: JenkinsConfig[];
  isLoading: boolean;
  isGithubLoading: boolean;
  isJenkinsLoading: boolean;

  // Actions
  fetchAccounts: () => Promise<void>;
  fetchGitHubConfigs: () => Promise<void>;
  fetchJenkinsConfigs: () => Promise<void>;
  removeAccount: (account: CloudAccount) => Promise<boolean>;
  removeGitHubConfig: (config: GitHubConfig) => Promise<boolean>;
  removeJenkinsConfig: (config: JenkinsConfig) => Promise<boolean>;
  verifyAccount: (details: VerificationDetails) => Promise<{ success: boolean; error?: string }>;
  updateBillingTable: (accountDbId: number, billingTable: string) => Promise<{ success: boolean; error?: string }>;

  // Helpers
  getStatusColor: (status: string, type: 'badge' | 'icon') => string;
}

export function useAccountManager(): UseAccountManagerReturn {
  const [accounts, setAccounts] = useState<CloudAccount[]>([]);
  const [githubConfigs, setGithubConfigs] = useState<GitHubConfig[]>([]);
  const [jenkinsConfigs, setJenkinsConfigs] = useState<JenkinsConfig[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isGithubLoading, setIsGithubLoading] = useState(true);
  const [isJenkinsLoading, setIsJenkinsLoading] = useState(true);

  /**
   * Fetch cloud accounts
   */
  const fetchAccounts = useCallback(async () => {
    setIsLoading(true);
    try {
      const data = await apiClient.get<CloudAccount[]>('/api/xamops/account-manager/accounts');
      setAccounts(data);
    } catch (err) {
      console.error('Failed to load accounts:', err);
    } finally {
      setIsLoading(false);
    }
  }, []);

  /**
   * Fetch GitHub configurations
   */
  const fetchGitHubConfigs = useCallback(async () => {
    setIsGithubLoading(true);
    try {
      const data = await apiClient.get<GitHubConfig[]>('/api/cicd/config/github');
      setGithubConfigs(data);
    } catch (err) {
      console.error('Failed to load GitHub configs:', err);
    } finally {
      setIsGithubLoading(false);
    }
  }, []);

  /**
   * Fetch Jenkins configurations
   */
  const fetchJenkinsConfigs = useCallback(async () => {
    setIsJenkinsLoading(true);
    try {
      const data = await apiClient.get<JenkinsConfig[]>('/api/cicd/config/jenkins');
      console.log('🏗️ Jenkins Configs received:', data);
      setJenkinsConfigs(data);
    } catch (err) {
      console.error('Failed to load Jenkins configs:', err);
    } finally {
      setIsJenkinsLoading(false);
    }
  }, []);

  /**
   * Remove a cloud account
   */
  const removeAccount = useCallback(async (account: CloudAccount): Promise<boolean> => {
    try {
      const res = await apiFetch(`/api/xamops/account-manager/accounts/${account.dbId}`, {
        method: 'DELETE',
      });

      if (!res.ok) {
        let errorMessage = 'Failed to remove account.';
        try {
          const errorData = await res.json();
          errorMessage = errorData.message || errorData.error || errorMessage;
        } catch {
          try {
            const errorText = await res.text();
            if (errorText) errorMessage = errorText;
          } catch {
            // Keep default message
          }
        }

        // Check if it's a constraint violation
        if (errorMessage.includes('constraint') || errorMessage.includes('ConstraintViolationException')) {
          throw new Error(
            'Cannot delete account because it has associated data (costs, resources, billing records, etc.).\n\n' +
            'The account must be cleaned up first before deletion.\n\n' +
            'Technical details: Database foreign key constraint violation.'
          );
        }

        throw new Error(`${errorMessage} (Status: ${res.status})`);
      }

      // Success - remove from UI
      setAccounts(prev => prev.filter(acc => acc.dbId !== account.dbId));

      // Clean up session storage if needed
      const selectedIds = JSON.parse(sessionStorage.getItem('selectedAccountIds') || '[]');
      const newSelectedIds = selectedIds.filter((id: string) => id !== account.id);
      if (selectedIds.length !== newSelectedIds.length) {
        sessionStorage.setItem('selectedAccountIds', JSON.stringify(newSelectedIds));
      }

      return true;
    } catch (err) {
      console.error('Error removing account:', err);
      throw err;
    }
  }, []);

  /**
   * Remove a GitHub config
   */
  const removeGitHubConfig = useCallback(async (config: GitHubConfig): Promise<boolean> => {
    try {
      const res = await apiFetch(`/api/cicd/config/github/${config.id}`, {
        method: 'DELETE',
      });

      if (!res.ok) {
        throw new Error('Failed to remove GitHub config.');
      }

      setGithubConfigs(prev => prev.filter(c => c.id !== config.id));
      return true;
    } catch (err) {
      console.error('Error removing GitHub config:', err);
      throw err;
    }
  }, []);

  /**
   * Remove a Jenkins config
   */
  const removeJenkinsConfig = useCallback(async (config: JenkinsConfig): Promise<boolean> => {
    try {
      const res = await apiFetch(`/api/cicd/config/jenkins/${config.id}`, {
        method: 'DELETE',
      });

      if (!res.ok) {
        throw new Error('Failed to delete config');
      }

      setJenkinsConfigs(prev => prev.filter(c => c.id !== config.id));
      return true;
    } catch (err) {
      console.error('Error removing Jenkins config:', err);
      throw err;
    }
  }, []);

  /**
   * Verify an AWS account
   */
  const verifyAccount = useCallback(async (details: VerificationDetails): Promise<{ success: boolean; error?: string }> => {
    try {
      const res = await apiFetch('/api/xamops/account-manager/verify-account', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(details),
      });

      if (!res.ok) {
        const errorData = await res.json().catch(() => ({}));
        return { success: false, error: errorData.message || 'Verification failed.' };
      }

      // Refresh accounts after verification
      await fetchAccounts();
      return { success: true };
    } catch (err) {
      return { success: false, error: err instanceof Error ? err.message : 'Unknown error' };
    }
  }, [fetchAccounts]);

  /**
   * Update billing export table for a GCP account
   */
  const updateBillingTable = useCallback(async (accountDbId: number, billingTable: string): Promise<{ success: boolean; error?: string }> => {
    try {
      const res = await apiFetch(`/api/xamops/account-manager/accounts/${accountDbId}/billing-table`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ billingExportTable: billingTable }),
      });

      if (!res.ok) {
        const errorData = await res.json().catch(() => ({}));
        return { success: false, error: errorData.message || 'Failed to update billing table.' };
      }

      // Refresh accounts after update
      await fetchAccounts();
      return { success: true };
    } catch (err) {
      return { success: false, error: err instanceof Error ? err.message : 'Unknown error' };
    }
  }, [fetchAccounts]);

  /**
   * Get status color classes
   */
  const getStatusColor = useCallback((status: string, type: 'badge' | 'icon'): string => {
    const colors: Record<string, { badge: string; icon: string }> = {
      'CONNECTED': {
        badge: 'bg-emerald-50 text-emerald-700 border-emerald-200 ring-1 ring-emerald-600/10',
        icon: 'text-emerald-500'
      },
      'PENDING': {
        badge: 'bg-amber-50 text-amber-700 border-amber-200 ring-1 ring-amber-600/10',
        icon: 'text-amber-500'
      },
      'FAILED': {
        badge: 'bg-red-50 text-red-700 border-red-200 ring-1 ring-red-600/10',
        icon: 'text-red-500'
      },
    };
    return colors[status]?.[type] || (type === 'badge'
      ? 'bg-gray-50 text-gray-700 border-gray-200 ring-1 ring-gray-600/10'
      : 'text-gray-500');
  }, []);

  // Initialize data on mount
  useEffect(() => {
    console.log('AccountManager: Initializing...');
    fetchAccounts();
    fetchGitHubConfigs();
    fetchJenkinsConfigs();
  }, [fetchAccounts, fetchGitHubConfigs, fetchJenkinsConfigs]);

  return {
    accounts,
    githubConfigs,
    jenkinsConfigs,
    isLoading,
    isGithubLoading,
    isJenkinsLoading,
    fetchAccounts,
    fetchGitHubConfigs,
    fetchJenkinsConfigs,
    removeAccount,
    removeGitHubConfig,
    removeJenkinsConfig,
    verifyAccount,
    updateBillingTable,
    getStatusColor,
  };
}
