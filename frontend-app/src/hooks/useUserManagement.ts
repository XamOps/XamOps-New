/**
 * useUserManagement Hook
 *
 * Custom hook for managing IAM users and roles data
 * Handles fetching, filtering, and formatting of user management data
 */

import { useState, useEffect, useCallback, useMemo } from 'react';
import type { IamUser, IamRole, IamRiskFields, UserManagementData } from '../types/userManagement';

/**
 * Orders identities worst-risk-first, then alphabetically within equal risk.
 *
 * <p>The name tiebreak matters: without it, every unassessed identity shares score 0 and
 * the browser's sort is free to reshuffle them between renders, so cards appear to jump
 * around while the user is reading them.
 */
function byRiskThenName<T extends IamRiskFields>(name: (item: T) => string) {
  return (a: T, b: T): number => {
    const delta = (b.riskScore ?? 0) - (a.riskScore ?? 0);
    if (delta !== 0) return delta;
    return name(a).localeCompare(name(b));
  };
}

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
   * When true, show only identities carrying a HIGH or CRITICAL risk.
   *
   * <p>Kept in the hook rather than the component so the KPI cards can act as filter
   * controls — clicking "Over-privileged" is the fastest path to the list that matters.
   */
  const [flaggedOnly, setFlaggedOnly] = useState(false);

  /**
   * Whether the permission audit could read every policy document.
   *
   * <p>Surfaced because an unaudited identity and a least-privilege identity both render
   * as an unflagged card. Without this the console would quietly imply the former is safe.
   */
  const [analysisDegraded, setAnalysisDegraded] = useState(false);
  const [analysisNote, setAnalysisNote] = useState<string | null>(null);

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
            setAnalysisDegraded(Boolean(data.selectedAccount.iamDetails.permissionAnalysisDegraded));
            setAnalysisNote(data.selectedAccount.iamDetails.permissionAnalysisNote ?? null);
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
   * Filtered users based on search query, worst risk first.
   *
   * <p>Risk-descending rather than alphabetical because the canvas shows cards, and a card
   * grid has no scannable column to sort by eye. The one identity holding
   * AdministratorAccess has to be in the first row or the view is decorative.
   */
  const filteredUsers = useMemo(() => {
    const lower = searchQuery.trim().toLowerCase();
    return users
      .filter(u => !flaggedOnly || u.excessPermissions)
      .filter(u => !lower
        || (u.userName && u.userName.toLowerCase().includes(lower))
        || (u.userId && u.userId.toLowerCase().includes(lower)))
      .slice()
      .sort(byRiskThenName(u => u.userName));
  }, [users, searchQuery, flaggedOnly]);

  /**
   * Filtered roles based on search query, worst risk first.
   */
  const filteredRoles = useMemo(() => {
    const lower = searchQuery.trim().toLowerCase();
    return roles
      .filter(r => !flaggedOnly || r.excessPermissions)
      .filter(r => !lower || r.roleName.toLowerCase().includes(lower))
      .slice()
      .sort(byRiskThenName(r => r.roleName));
  }, [roles, searchQuery, flaggedOnly]);

  /**
   * Risk tallies across users and roles combined.
   *
   * <p>Combined deliberately: "how exposed is this account's identity surface" is one
   * question, and splitting it per tab would let a critical role hide behind a clean
   * user list.
   */
  const riskCounts = useMemo(() => {
    const all: Array<IamUser | IamRole> = [...users, ...roles];
    const counts = { critical: 0, high: 0, medium: 0, low: 0, flagged: 0, assessed: 0 };
    for (const identity of all) {
      if (identity.riskLevel) counts.assessed += 1;
      if (identity.excessPermissions) counts.flagged += 1;
      switch (identity.riskLevel) {
        case 'CRITICAL': counts.critical += 1; break;
        case 'HIGH': counts.high += 1; break;
        case 'MEDIUM': counts.medium += 1; break;
        case 'LOW': counts.low += 1; break;
        default: break;
      }
    }
    return counts;
  }, [users, roles]);

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
   * Provider-appropriate names for the two identity kinds.
   *
   * <p>GCP says "Principals" rather than "Service Accounts" because the list is no longer only
   * service accounts: the analyzer adds the human users and groups that appear in the project
   * IAM policy, which are invisible to the service-account API. Roles differ too — GCP lists
   * only custom roles (the ~1,500 predefined ones are identical everywhere and unchangeable),
   * while Azure lists role definitions.
   */
  const labels = useMemo(() => ({
    users: provider === 'AWS' ? 'IAM Users' : 'Principals',
    roles: provider === 'GCP' ? 'Custom Roles' : provider === 'Azure' ? 'Role Definitions' : 'IAM Roles',
    userIdentity: provider === 'AWS' ? 'User Identity' : 'Principal',
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

    // Permission-risk surface, consumed by the Security Center's Identity tab.
    riskCounts,
    flaggedOnly,
    setFlaggedOnly,
    analysisDegraded,
    analysisNote,
    /**
     * Whether risk scoring ran at all for this provider.
     *
     * <p>GCP and Azure return the inventory shape without risk fields, so the canvas hides
     * the risk KPIs and red flags entirely there rather than rendering a misleading
     * "0 over-privileged".
     */
    riskAnalysisAvailable: riskCounts.assessed > 0,
  };
};
