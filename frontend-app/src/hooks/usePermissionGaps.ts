/**
 * Permission-gap lookup for the account(s) currently selected.
 *
 * Runs alongside normal page data rather than blocking it: the page still renders
 * whatever it could load, and this fills in the explanation for the parts that
 * came back empty. A failure here is always silent — an unavailable health check
 * must never be the reason a working page shows an error.
 */

import { useCallback, useEffect, useState } from 'react';
import { useLocation } from 'react-router-dom';
import { useAccounts } from '../contexts/AccountContext';
import apiClient from '../utils/apiClient';
import {
  AccountPermissionStatus,
  PermissionStatusResponse,
  featureForRoute,
} from '../types/permissions';

interface UsePermissionGapsResult {
  /** Accounts with something missing. Healthy accounts are filtered out. */
  impacted: AccountPermissionStatus[];
  isLoading: boolean;
  /** Feature key resolved from the current route, or null on a non-feature page. */
  feature: string | null;
  refresh: () => void;
  /** Clears stored gaps for an account and asks the backend to re-test the role. */
  recheck: (accountId: string) => Promise<string | null>;
}

export const usePermissionGaps = (): UsePermissionGapsResult => {
  const { selectedAccountIds, accounts } = useAccounts();
  const location = useLocation();
  const [impacted, setImpacted] = useState<AccountPermissionStatus[]>([]);
  const [isLoading, setIsLoading] = useState(false);

  const feature = featureForRoute(location.pathname);

  // Only AWS accounts are instrumented; asking about a GCP-only selection would
  // always come back empty and just add a request per navigation.
  const awsAccountIds = selectedAccountIds.filter(id =>
    accounts.some(account => account.id === id && account.provider === 'AWS')
  );
  const accountKey = awsAccountIds.join(',');

  const load = useCallback(async () => {
    if (!accountKey) {
      setImpacted([]);
      return;
    }
    setIsLoading(true);
    try {
      const params = new URLSearchParams({ accountIds: accountKey });
      if (feature) params.set('feature', feature);

      const data = await apiClient.get<PermissionStatusResponse>(
        `/api/xamops/account-health/permissions?${params.toString()}`
      );
      setImpacted((data.accounts || []).filter(account => account.health !== 'HEALTHY'));
    } catch {
      // Health data is an enhancement — stay quiet and show nothing.
      setImpacted([]);
    } finally {
      setIsLoading(false);
    }
  }, [accountKey, feature]);

  useEffect(() => {
    let cancelled = false;
    // Let the page's own data start first; the gap is only meaningful once the
    // backend has actually attempted the calls this page needs.
    const timer = setTimeout(() => {
      if (!cancelled) load();
    }, 1500);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [load]);

  const recheck = useCallback(
    async (accountId: string): Promise<string | null> => {
      try {
        const res = await apiClient.post<{ message?: string }>(
          `/api/xamops/account-health/permissions/recheck?accountId=${encodeURIComponent(accountId)}`
        );
        setImpacted(prev => prev.filter(account => account.accountId !== accountId));
        return res?.message ?? 'Access re-check started.';
      } catch {
        return null;
      }
    },
    []
  );

  return { impacted, isLoading, feature, refresh: load, recheck };
};

export default usePermissionGaps;
