/**
 * useUserManagement Hook
 *
 * Custom hook for managing IAM users and roles data
 * Handles fetching, filtering, and formatting of user management data
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import type { IamUser, IamRole, UserManagementData } from '../types/userManagement';

export const useUserManagement = () => {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [accountName, setAccountName] = useState('');
  const [accountId, setAccountId] = useState('');
  const [provider, setProvider] = useState<'AWS' | 'GCP' | 'Azure'>('AWS');
  const [users, setUsers] = useState<IamUser[]>([]);
  const [roles, setRoles] = useState<IamRole[]>([]);
  const [searchQuery, setSearchQuery] = useState('');

  /**
   * Determine provider from cached accounts
   */
  const determineProvider = useCallback((accountId: string) => {
    try {
      const cached = sessionStorage.getItem('cached_accounts');
      if (cached) {
        const accounts = JSON.parse(cached);
        const account = accounts.find((a: any) => a.id === accountId);
        if (account && account.provider) {
          setProvider(account.provider);
          return;
        }
      }
    } catch (e) {
      console.error('Error determining provider', e);
    }
  }, []);

  /**
   * Load account from storage
   */
  const loadAccountFromStorage = useCallback(() => {
    let targetId: string | null = null;
    const storedIds = sessionStorage.getItem('selectedAccountIds');

    if (storedIds) {
      try {
        const ids = JSON.parse(storedIds);
        if (Array.isArray(ids) && ids.length > 0) {
          targetId = ids[0];
        }
      } catch (e) {
        console.error('Error parsing IDs', e);
      }
    }

    if (!targetId) {
      targetId = localStorage.getItem('selectedAccountId');
    }

    if (!targetId) {
      targetId = new URLSearchParams(window.location.search).get('accountId');
    }

    if (targetId) {
      if (accountId !== targetId || users.length === 0) {
        setAccountId(targetId);
        determineProvider(targetId);
        fetchData(false, targetId);
      }
    } else {
      setError('Please select an account from the sidebar.');
      setUsers([]);
      setRoles([]);
    }
  }, [accountId, users.length, determineProvider]);

  /**
   * Fetch IAM data from API
   */
  const fetchData = useCallback(async (forceRefresh = false, specificAccountId: string | null = null) => {
    const idToFetch = specificAccountId || accountId;
    if (!idToFetch) return;

    setLoading(true);
    setError(null);

    try {
      const url = `/api/xamops/dashboard/data?accountId=${idToFetch}&forceRefresh=${forceRefresh}&_t=${new Date().getTime()}`;
      console.log('Fetching:', url);

      const response = await fetch(url, {
        method: 'GET',
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json'
        },
        credentials: 'include'
      });

      if (!response.ok) {
        const text = await response.text();
        throw new Error(`Status: ${response.status} - ${text.substring(0, 500)}`);
      }

      const data: UserManagementData = await response.json();

      if (data) {
        // Capture Provider
        setProvider(data.selectedProvider || 'AWS');

        if (data.selectedAccount) {
          setAccountName(data.selectedAccount.name);
          setAccountId(data.selectedAccount.id);

          if (!data.selectedAccount.iamDetails && !forceRefresh) {
            console.warn('Stale cache detected (missing iamDetails). Auto-refreshing...');
            await fetchData(true, idToFetch);
            return;
          }

          if (data.selectedAccount.iamDetails) {
            setUsers(data.selectedAccount.iamDetails.users || []);
            setRoles(data.selectedAccount.iamDetails.roles || []);
          } else {
            console.warn('IAM Details missing even after refresh.');
            setUsers([]);
            setRoles([]);
            if (forceRefresh) {
              setError('Backend returned incomplete data (iamDetails is null).');
            }
          }
        }
      } else {
        throw new Error('Invalid response format');
      }
    } catch (err) {
      console.error('Fetch error:', err);
      setError(err instanceof Error ? err.message : 'Failed to load data');
    } finally {
      setLoading(false);
    }
  }, [accountId]);

  /**
   * Initialize data on mount
   */
  useEffect(() => {
    loadAccountFromStorage();

    // Listen for account changes
    const handleAccountChange = () => loadAccountFromStorage();
    window.addEventListener('account-changed', handleAccountChange);

    return () => {
      window.removeEventListener('account-changed', handleAccountChange);
    };
  }, [loadAccountFromStorage]);

  /**
   * Filtered users based on search query
   */
  const filteredUsers = useMemo(() => {
    if (!searchQuery) return users;
    const lower = searchQuery.toLowerCase();
    return users.filter(u =>
      (u.userName && u.userName.toLowerCase().includes(lower)) ||
      (u.userId && u.userId.toLowerCase().includes(lower))
    );
  }, [users, searchQuery]);

  /**
   * Filtered roles based on search query
   */
  const filteredRoles = useMemo(() => {
    if (!searchQuery) return roles;
    const lower = searchQuery.toLowerCase();
    return roles.filter(r =>
      r.roleName.toLowerCase().includes(lower)
    );
  }, [roles, searchQuery]);

  /**
   * Format date string
   */
  const formatDate = useCallback((dateString: string | null): string => {
    if (!dateString || dateString === 'Never') return 'Never';
    try {
      const date = new Date(dateString);
      if (isNaN(date.getTime())) return dateString;
      return date.toLocaleDateString('en-US', {
        year: 'numeric',
        month: 'short',
        day: 'numeric'
      });
    } catch (e) {
      return dateString;
    }
  }, []);

  /**
   * Check if date is older than 90 days
   */
  const isOldDate = useCallback((dateString: string | null): boolean => {
    if (!dateString || dateString === 'Never') return true;
    const date = new Date(dateString);
    const ninetyDaysAgo = new Date();
    ninetyDaysAgo.setDate(ninetyDaysAgo.getDate() - 90);
    return date < ninetyDaysAgo;
  }, []);

  /**
   * Count users without MFA (AWS only)
   */
  const countNoMfa = useMemo(() => {
    if (provider !== 'AWS') return 0;
    return users.filter(u => !u.mfaEnabled).length;
  }, [provider, users]);

  /**
   * Copy text to clipboard
   */
  const copyToClipboard = useCallback(async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
    } catch (e) {
      console.error('Failed to copy to clipboard', e);
    }
  }, []);

  /**
   * Get labels based on provider
   */
  const labels = useMemo(() => ({
    users: provider === 'GCP' ? 'Service Accounts' : provider === 'Azure' ? 'Principals' : 'IAM Users',
    roles: 'IAM Roles',
    userIdentity: provider === 'GCP' ? 'Service Account' : provider === 'Azure' ? 'Principal Name' : 'User Identity',
  }), [provider]);

  return {
    loading,
    error,
    accountName,
    accountId,
    provider,
    users,
    roles,
    searchQuery,
    setSearchQuery,
    filteredUsers,
    filteredRoles,
    formatDate,
    isOldDate,
    countNoMfa,
    copyToClipboard,
    labels,
    fetchData,
    isAws: provider === 'AWS',
    isGcp: provider === 'GCP',
    isAzure: provider === 'Azure',
  };
};
