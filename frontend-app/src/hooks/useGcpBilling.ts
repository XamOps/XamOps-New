/**
 * useGcpBilling Hook
 *
 * Custom hook for fetching and managing GCP billing dashboard data
 * Converted from: billops/gcp-billing.html
 *
 * What stayed the same:
 * - Same API endpoints: /api/billops/billing/gcp/dashboard/cached and /api/billops/billing/gcp/dashboard/{accountId}
 * - Same query parameters (accountIdentifier, year, month)
 * - Same data structure and fields
 * - Cache-first strategy with background refresh
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Uses useAccounts context for account selection
 * - Added proper TypeScript types
 */

import { useState, useEffect, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import type {
  GcpBillingSummaryData,
  GcpMonthOption,
} from '../types/gcpBilling';

interface UseGcpBillingResult {
  // Data
  dashboardData: GcpBillingSummaryData;

  // Loading states
  loading: boolean;
  isUpdating: boolean;

  // Errors
  error: string | null;
  updateError: string | null;

  // Filters
  selectedYear: number;
  selectedMonth: string;
  availableYears: number[];
  months: GcpMonthOption[];

  // Computed
  hasData: boolean;
  accountId: string | null;

  // Methods
  setSelectedYear: (year: number) => void;
  setSelectedMonth: (month: string) => void;
  fetchFreshData: () => Promise<void>;
  loadInitialData: () => void;
}

const MONTHS: GcpMonthOption[] = [
  { value: '01', label: 'Jan' },
  { value: '02', label: 'Feb' },
  { value: '03', label: 'Mar' },
  { value: '04', label: 'Apr' },
  { value: '05', label: 'May' },
  { value: '06', label: 'Jun' },
  { value: '07', label: 'Jul' },
  { value: '08', label: 'Aug' },
  { value: '09', label: 'Sep' },
  { value: '10', label: 'Oct' },
  { value: '11', label: 'Nov' },
  { value: '12', label: 'Dec' },
];

export const useGcpBilling = (): UseGcpBillingResult => {
  const { selectedAccountIds } = useAccounts();

  // Get account ID from URL or context
  const getAccountId = useCallback((): string | null => {
    const urlParams = new URLSearchParams(window.location.search);
    const urlAccountIds = urlParams.get('accountIds') || urlParams.get('projectId');

    if (urlAccountIds) {
      return urlAccountIds;
    }

    if (selectedAccountIds.length > 0) {
      return selectedAccountIds[0]; // GCP billing only supports single account
    }

    return null;
  }, [selectedAccountIds]);

  // State
  const [accountId, setAccountId] = useState<string | null>(getAccountId());
  const [loading, setLoading] = useState<boolean>(true);
  const [isUpdating, setIsUpdating] = useState<boolean>(false);
  const [error, setError] = useState<string | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);

  const currentDate = new Date();
  const [selectedYear, setSelectedYear] = useState<number>(currentDate.getFullYear());
  const [selectedMonth, setSelectedMonth] = useState<string>('');

  const [dashboardData, setDashboardData] = useState<GcpBillingSummaryData>({
    totalCost: 0,
    costThisMonth: 0,
    costLastMonth: 0,
    costHistory: [],
    serviceBreakdown: [],
  });

  // Available years
  const availableYears = [
    currentDate.getFullYear(),
    currentDate.getFullYear() - 1,
    currentDate.getFullYear() - 2,
  ];

  // Has data check
  const hasData =
    dashboardData &&
    (dashboardData.costHistory?.length > 0 || dashboardData.serviceBreakdown?.length > 0);

  /**
   * Fetch fresh data from API (bypasses cache)
   */
  const fetchFreshData = useCallback(async () => {
    const currentAccountId = getAccountId();

    if (!currentAccountId) {
      setError('No GCP Account ID specified. Please select an account.');
      setLoading(false);
      setIsUpdating(false);
      return;
    }

    setIsUpdating(true);
    setUpdateError(null);

    console.log(
      `[GCP Billing] Fetching fresh data. Period: ${selectedYear}${selectedMonth ? `-${selectedMonth}` : ''}`
    );

    const params = new URLSearchParams();
    if (selectedYear) params.set('year', String(selectedYear));
    if (selectedMonth) params.set('month', selectedMonth);

    const freshUrl = `/api/billops/billing/gcp/dashboard/${currentAccountId}${params.toString() ? `?${params.toString()}` : ''}`;

    try {
      const response = await fetch(freshUrl, { credentials: 'include' });

      if (!response.ok) {
        const text = await response.text();
        throw new Error(`Failed: ${response.status}. ${text}`);
      }

      const freshData = await response.json();
      setDashboardData(
        freshData || {
          totalCost: 0,
          costThisMonth: 0,
          costLastMonth: 0,
          costHistory: [],
          serviceBreakdown: [],
        }
      );
      setError(null);

      console.log('[GCP Billing] Fresh data received successfully');
    } catch (err) {
      console.error('[GCP Billing] Fresh data error:', err);
      const errorMsg = err instanceof Error ? err.message : 'Failed to retrieve billing data.';
      if (loading) {
        setError(errorMsg);
      } else {
        setUpdateError(errorMsg);
      }
    } finally {
      setLoading(false);
      setIsUpdating(false);
    }
  }, [selectedYear, selectedMonth, getAccountId, loading]);

  /**
   * Load initial data (cache-first with background refresh)
   */
  const loadInitialData = useCallback(() => {
    const currentAccountId = getAccountId();

    if (!currentAccountId) {
      setError('No GCP Account ID specified. Please select an account.');
      setLoading(false);
      return;
    }

    setLoading(true);
    setIsUpdating(false);
    setError(null);
    setUpdateError(null);

    console.log(
      `[GCP Billing] Loading initial data. Period: ${selectedYear}${selectedMonth ? `-${selectedMonth}` : ''}`
    );

    const params = new URLSearchParams({
      accountIdentifier: currentAccountId,
    });
    if (selectedYear) params.set('year', String(selectedYear));
    if (selectedMonth) params.set('month', selectedMonth);

    const cachedUrl = `/api/billops/billing/gcp/dashboard/cached?${params.toString()}`;

    fetch(cachedUrl, { credentials: 'include' })
      .then((res) => {
        if (res.ok) return res.json();
        if (res.status === 404) return null;
        throw new Error(`Cache check failed: ${res.status}`);
      })
      .then((cachedData) => {
        if (cachedData) {
          console.log('[GCP Billing] Cache hit, displaying cached data');
          setDashboardData(cachedData);
          setLoading(false);

          // Fetch fresh data in background
          setIsUpdating(true);
          fetchFreshData();
        } else {
          console.log('[GCP Billing] Cache miss, fetching fresh data');
          fetchFreshData();
        }
      })
      .catch((err) => {
        console.error('[GCP Billing] Cache check error:', err);
        setError('Error checking cache. Fetching fresh data...');
        fetchFreshData();
      });
  }, [selectedYear, selectedMonth, getAccountId, fetchFreshData]);

  // Update accountId when selectedAccountIds changes
  useEffect(() => {
    const newAccountId = getAccountId();
    if (newAccountId !== accountId) {
      setAccountId(newAccountId);
    }
  }, [selectedAccountIds, getAccountId, accountId]);

  // Initial data fetch
  useEffect(() => {
    if (accountId) {
      loadInitialData();
    }
  }, [accountId, selectedYear, selectedMonth]);

  // Listen for account changes
  useEffect(() => {
    const handleAccountChanged = (event: CustomEvent) => {
      console.log('[GCP Billing] Account changed event received:', event.detail);
      const newAccountId = event.detail.projectId || event.detail.accountId || event.detail.accountIds?.[0];

      if (newAccountId && newAccountId !== accountId) {
        console.log('[GCP Billing] Updating account ID to:', newAccountId);
        setAccountId(newAccountId);
        setError(null);
      }
    };

    window.addEventListener('account-changed', handleAccountChanged as EventListener);

    return () => {
      window.removeEventListener('account-changed', handleAccountChanged as EventListener);
    };
  }, [accountId]);

  return {
    dashboardData,
    loading,
    isUpdating,
    error,
    updateError,
    selectedYear,
    selectedMonth,
    availableYears,
    months: MONTHS,
    hasData,
    accountId,
    setSelectedYear,
    setSelectedMonth,
    fetchFreshData,
    loadInitialData,
  };
};

export default useGcpBilling;
