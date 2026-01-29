/**
 * useBilling Hook
 *
 * Custom hook for fetching and managing billing dashboard data
 * Converted from: billops/billing.html
 *
 * What stayed the same:
 * - Same API endpoints: /api/billops/billing and /api/billops/detailed-breakdown
 * - Same query parameters (accountIds, year, month, forceRefresh)
 * - Same data structure and fields
 *
 * What changed:
 * - Migrated from Alpine.js to React with TypeScript
 * - Uses useAccounts context for account selection
 * - Added proper TypeScript types
 */

import { useState, useEffect, useCallback } from 'react';
import { useAccounts } from '../contexts/AccountContext';
import type {
  BillingSummaryData,
  DetailedServiceBreakdown,
  MonthOption,
} from '../types/billing';

interface UseBillingResult {
  // Data
  billingData: BillingSummaryData;
  detailedBreakdown: DetailedServiceBreakdown[];

  // Loading states
  loading: boolean;
  isUpdating: boolean;

  // Errors
  error: string | null;
  updateError: string | null;

  // Filters
  selectedYear: number;
  selectedMonth: number;
  availableYears: number[];
  availableMonths: MonthOption[];

  // Computed
  hasData: boolean;
  accountId: string | null;

  // Methods
  setSelectedYear: (year: number) => void;
  setSelectedMonth: (month: number) => void;
  fetchData: (forceRefresh?: boolean) => Promise<void>;
  handleFilterChange: (year: number, month: number) => void;
}

const AVAILABLE_MONTHS: MonthOption[] = [
  { value: 1, name: 'Jan' },
  { value: 2, name: 'Feb' },
  { value: 3, name: 'Mar' },
  { value: 4, name: 'Apr' },
  { value: 5, name: 'May' },
  { value: 6, name: 'Jun' },
  { value: 7, name: 'Jul' },
  { value: 8, name: 'Aug' },
  { value: 9, name: 'Sep' },
  { value: 10, name: 'Oct' },
  { value: 11, name: 'Nov' },
  { value: 12, name: 'Dec' },
];

export const useBilling = (): UseBillingResult => {
  const { selectedAccountIds } = useAccounts();

  // Get account ID from URL or context
  const getAccountId = useCallback((): string | null => {
    const urlParams = new URLSearchParams(window.location.search);
    const urlAccountIds = urlParams.get('accountIds') || urlParams.get('accountId');

    if (urlAccountIds) {
      return urlAccountIds;
    }

    if (selectedAccountIds.length > 0) {
      return selectedAccountIds.join(',');
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
  const [selectedMonth, setSelectedMonth] = useState<number>(currentDate.getMonth() + 1);

  const [billingData, setBillingData] = useState<BillingSummaryData>({
    costHistory: [],
    serviceBreakdown: [],
  });
  const [detailedBreakdown, setDetailedBreakdown] = useState<DetailedServiceBreakdown[]>([]);

  // Available years
  const availableYears = [currentDate.getFullYear(), currentDate.getFullYear() - 1];

  // Has data check
  const hasData =
    (billingData.costHistory && billingData.costHistory.length > 0) ||
    (detailedBreakdown && detailedBreakdown.length > 0);

  /**
   * Fetch billing data from API
   */
  const fetchData = useCallback(
    async (forceRefresh = false) => {
      const currentAccountId = getAccountId();

      if (!currentAccountId) {
        setError('No AWS Account ID(s) specified. Please select an account.');
        setLoading(false);
        return;
      }

      if (forceRefresh) {
        setIsUpdating(true);
        setUpdateError(null);
      } else {
        setLoading(true);
        setError(null);
      }

      console.log(
        `[Billing] Fetching data. Period: ${selectedYear}-${selectedMonth}, ForceRefresh: ${forceRefresh}`
      );

      const params = new URLSearchParams({
        accountIds: currentAccountId,
        year: String(selectedYear),
        month: String(selectedMonth),
        forceRefresh: String(forceRefresh),
      });

      const summaryUrl = `/api/billops/billing?${params.toString()}`;
      const detailedUrl = `/api/billops/detailed-breakdown?${params.toString()}`;

      try {
        const [summaryResponse, detailedResponse] = await Promise.all([
          fetch(summaryUrl, { credentials: 'include' }),
          fetch(detailedUrl, { credentials: 'include' }),
        ]);

        if (!summaryResponse.ok) {
          throw new Error(`Summary fetch failed: ${summaryResponse.status}`);
        }
        if (!detailedResponse.ok) {
          throw new Error(`Detailed fetch failed: ${detailedResponse.status}`);
        }

        const summaryData = await summaryResponse.json();
        const detailedData = await detailedResponse.json();

        console.log('[Billing] Data received successfully');

        setBillingData(summaryData || { costHistory: [], serviceBreakdown: [] });
        setDetailedBreakdown(detailedData || []);
        setError(null);
      } catch (err) {
        console.error('[Billing] Network error:', err);
        const msg = 'Failed to load billing data. Please check your connection.';

        if (forceRefresh) {
          setUpdateError('Failed to refresh data. Please try again.');
        } else {
          if (!hasData) {
            setError(msg);
          } else {
            setUpdateError(msg);
          }
        }
      } finally {
        setLoading(false);
        setIsUpdating(false);
      }
    },
    [selectedYear, selectedMonth, getAccountId, hasData]
  );

  /**
   * Handle filter change (year/month)
   */
  const handleFilterChange = useCallback(
    (year: number, month: number) => {
      setSelectedYear(year);
      setSelectedMonth(month);
      setError(null);
      setUpdateError(null);
    },
    []
  );

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
      fetchData(false);
    }
  }, [accountId, selectedYear, selectedMonth]);

  // Listen for account changes
  useEffect(() => {
    const handleAccountChanged = (event: CustomEvent) => {
      console.log('[Billing] Account changed event received:', event.detail);
      const newAccountId = event.detail.accountIds
        ? event.detail.accountIds.join(',')
        : event.detail.accountId;

      if (newAccountId && newAccountId !== accountId) {
        console.log('[Billing] Updating account ID to:', newAccountId);
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
    billingData,
    detailedBreakdown,
    loading,
    isUpdating,
    error,
    updateError,
    selectedYear,
    selectedMonth,
    availableYears,
    availableMonths: AVAILABLE_MONTHS,
    hasData,
    accountId,
    setSelectedYear,
    setSelectedMonth,
    fetchData,
    handleFilterChange,
  };
};

export default useBilling;
