/**
 * useAgreements Hook
 *
 * Custom hook for fetching and managing agreements data
 * Converted from: billops/agreements.html
 *
 * What stayed the same:
 * - Same API endpoint: /api/billops/agreements/account/{accountId}
 * - Same data structure and fields
 * - Account selection via URL params and account-changed events
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Uses useAccounts context for account selection
 * - Added proper TypeScript types
 */

import { useState, useEffect, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import type { Agreement, UseAgreementsResult } from '../types/agreements';

export const useAgreements = (): UseAgreementsResult => {
  const { selectedAccountIds } = useAccounts();

  // Get account ID from URL or context
  const getAccountId = useCallback((): string | null => {
    const urlParams = new URLSearchParams(window.location.search);
    const urlAccountIds = urlParams.get('accountIds') || urlParams.get('accountId');

    if (urlAccountIds) {
      return urlAccountIds.split(',')[0]; // Take first account ID
    }

    if (selectedAccountIds.length > 0) {
      return String(selectedAccountIds[0]);
    }

    return null;
  }, [selectedAccountIds]);

  // State
  const [accountId, setAccountId] = useState<string | null>(getAccountId());
  const [accountName, setAccountName] = useState<string>('');
  const [agreements, setAgreements] = useState<Agreement[]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * Fetch account name from cached accounts in sessionStorage
   */
  const fetchAccountName = useCallback(() => {
    if (!accountId) {
      setAccountName('');
      return;
    }

    try {
      const accounts = JSON.parse(sessionStorage.getItem('cached_accounts') || '[]');
      const account = accounts.find((a: any) => String(a.id) === String(accountId));
      if (account) {
        setAccountName(account.name);
      }
    } catch (err) {
      console.error('[Agreements] Failed to fetch account name:', err);
    }
  }, [accountId]);

  /**
   * Fetch agreements from API
   */
  const fetchAgreements = useCallback(async () => {
    if (!accountId) {
      setAgreements([]);
      setError(null);
      return;
    }

    setLoading(true);
    setError(null);

    console.log(`[Agreements] Fetching agreements for account: ${accountId}`);

    try {
      const response = await fetch(`/api/billops/agreements/account/${accountId}`, {
        credentials: 'include',
        headers: {
          'Accept': 'application/json',
        },
      });

      if (!response.ok) {
        throw new Error(`Failed to fetch agreements: ${response.status}`);
      }

      const data: Agreement[] = await response.json();
      console.log('[Agreements] Data received:', data.length, 'agreements');

      setAgreements(data);
      setError(null);
    } catch (err) {
      console.error('[Agreements] Fetch error:', err);
      setError('Failed to load agreements. Please try again later.');
      setAgreements([]);
    } finally {
      setLoading(false);
    }
  }, [accountId]);

  // Update accountId when selectedAccountIds changes
  useEffect(() => {
    const newAccountId = getAccountId();
    if (newAccountId !== accountId) {
      setAccountId(newAccountId);
    }
  }, [selectedAccountIds, getAccountId, accountId]);

  // Fetch account name when accountId changes
  useEffect(() => {
    fetchAccountName();
  }, [accountId, fetchAccountName]);

  // Fetch agreements when accountId changes
  useEffect(() => {
    if (accountId) {
      fetchAgreements();
    }
  }, [accountId, fetchAgreements]);

  // Listen for account changes
  useEffect(() => {
    const handleAccountChanged = (event: CustomEvent) => {
      console.log('[Agreements] Account changed event received:', event.detail);
      const newAccountId = event.detail.accountId ||
                          (event.detail.accountIds && event.detail.accountIds[0]);

      if (newAccountId && String(newAccountId) !== accountId) {
        console.log('[Agreements] Updating account ID to:', newAccountId);
        setAccountId(String(newAccountId));
      }
    };

    window.addEventListener('account-changed', handleAccountChanged as EventListener);

    return () => {
      window.removeEventListener('account-changed', handleAccountChanged as EventListener);
    };
  }, [accountId]);

  return {
    agreements,
    loading,
    error,
    accountId,
    accountName,
    fetchAgreements,
  };
};

export default useAgreements;
