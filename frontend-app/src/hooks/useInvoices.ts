/**
 * useInvoices Hook
 *
 * Converted from: billops/invoices.html (invoicePage Alpine.js component)
 *
 * Manages invoice list and detail views with:
 * - LocalStorage caching for instant load
 * - Background data refresh
 * - Filter by year/month
 * - Detail view with drilldown breakdown
 * - PDF download functionality
 *
 * What stayed the same:
 * - Same API endpoint: /api/admin/invoices
 * - Same caching strategy (localStorage)
 * - Same filtering logic for AWS, GCP, Azure
 * - Same data transformation for drilldown
 * - All business logic preserved exactly
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { apiFetch } from '../utils/apiClient';
import type {
  Invoice,
  ServiceCost,
  RegionCost,
  ResourceCost,
  Month,
  UseInvoicesReturn,
} from '../types/invoices';
import {
  initNotificationService,
  notifyNewInvoice
} from '../utils/notificationService';
import { useAccounts } from '../contexts/AccountContext';
import { useAuth } from '../contexts/AuthContext';

export const useInvoices = (): UseInvoicesReturn => {
  const { selectedAccountIds, accounts, isLoading: accountsLoading } = useAccounts();
  const { userProfile } = useAuth();
  const cacheKeyRef = useRef(`invoices_list_admin:${userProfile?.username ?? 'unknown'}`);
  useEffect(() => {
    cacheKeyRef.current = `invoices_list_admin:${userProfile?.username ?? 'unknown'}`;
  }, [userProfile?.username]);

  // Data state
  const [allInvoices, setAllInvoices] = useState<Invoice[]>([]);
  const [filteredInvoices, setFilteredInvoices] = useState<Invoice[]>([]);
  const [selectedInvoice, setSelectedInvoice] = useState<Invoice | null>(null);
  const [detailedBreakdown, setDetailedBreakdown] = useState<ServiceCost[]>([]);
  const [expandedServices, setExpandedServices] = useState<Record<string, boolean>>({});
  const [expandedRegions, setExpandedRegions] = useState<Record<string, boolean>>({});

  // Filter state
  const currentYear = new Date().getFullYear();
  const [years] = useState<number[]>([currentYear, currentYear - 1]);
  const [selectedYear, setSelectedYear] = useState<string>('all');
  const [selectedMonth, setSelectedMonth] = useState<string>('all');
  const [months] = useState<Month[]>([
    { name: 'January', value: 1 },
    { name: 'February', value: 2 },
    { name: 'March', value: 3 },
    { name: 'April', value: 4 },
    { name: 'May', value: 5 },
    { name: 'June', value: 6 },
    { name: 'July', value: 7 },
    { name: 'August', value: 8 },
    { name: 'September', value: 9 },
    { name: 'October', value: 10 },
    { name: 'November', value: 11 },
    { name: 'December', value: 12 },
  ]);

  // Account state — driven by AccountContext (same pattern as useBillOpsTickets)
  const selectedAccountId = selectedAccountIds.length > 0 ? selectedAccountIds[0] : null;
  const [selectedAccountName, setSelectedAccountName] = useState<string>('');

  // View state
  const [viewState, setViewState] = useState<'list' | 'detail'>('list');
  const [listLoading, setListLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [isUpdating, setIsUpdating] = useState(false);

  // Error state
  const [error, setError] = useState<string | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [lastUpdatedDisplay, setLastUpdatedDisplay] = useState<string>('');

  const isMountedRef = useRef(true);

  // Refs for tracking new invoices (for notifications)
  const previousInvoiceIdsRef = useRef<Set<number>>(new Set());
  const isInitialLoadRef = useRef<boolean>(true);
  const pollingIntervalRef = useRef<NodeJS.Timeout | null>(null);

  // Computed
  const hasData = allInvoices && allInvoices.length > 0;

  /**
   * Update last updated display
   */
  const updateLastUpdatedDisplay = useCallback(() => {
    if (!lastUpdated) return;
    const date = new Date(lastUpdated);
    setLastUpdatedDisplay(date.toLocaleString());
  }, [lastUpdated]);

  useEffect(() => {
    updateLastUpdatedDisplay();
  }, [lastUpdated, updateLastUpdatedDisplay]);


  /**
   * Fetch data from API
   */
  const fetchData = useCallback(
    async (forceRefresh: boolean = false) => {
      // If forceRefresh is manually triggered, or if we have no data yet
      if (forceRefresh || !hasData) {
        setIsUpdating(true);
        setUpdateError(null);
      } else {
        // Hidden background refresh
        setIsUpdating(true);
      }

      try {
        const response = await apiFetch(`/api/admin/invoices?forceRefresh=${forceRefresh}`, {
          credentials: 'include',
        });

        if (!response.ok) {
          throw new Error('Failed to load invoice list.');
        }

        const data: Invoice[] = await response.json();
        console.log('✅ [Invoices] Fresh data received.');
        setAllInvoices(data);

        // Update Cache & Timestamp
        const now = Date.now();
        setLastUpdated(now);

        localStorage.setItem(
          cacheKeyRef.current,
          JSON.stringify({
            data: data,
            timestamp: now,
          })
        );
      } catch (err) {
        console.error('Error fetching invoice list:', err);
        const msg = 'Failed to load fresh invoice data.';

        if (forceRefresh || !hasData) {
          setError(msg);
        } else {
          setUpdateError(msg + ' (Showing cached data)');
        }
      } finally {
        if (isMountedRef.current) {
          setListLoading(false);
          setIsUpdating(false);
        }
      }
    },
    [hasData]
  );

  /**
   * Filter and display invoices
   */
  const filterAndDisplayInvoices = useCallback(() => {
    setViewState('list');
    setSelectedInvoice(null);

    // Filter by account (if account is selected)
    let invoices = selectedAccountId
      ? allInvoices.filter(
        (inv) =>
          (inv.awsAccountId === selectedAccountId ||
            inv.gcpProjectId === selectedAccountId ||
            inv.azureSubscriptionId === selectedAccountId) &&
          inv.status === 'FINALIZED'
      )
      : allInvoices.filter((inv) => inv.status === 'FINALIZED'); // Show all invoices if no account selected

    // Filter by year
    if (selectedYear !== 'all') {
      invoices = invoices.filter((inv) => inv.billingPeriod.startsWith(selectedYear));
    }

    // Filter by month
    if (selectedMonth !== 'all') {
      const monthStr = `-${String(selectedMonth).padStart(2, '0')}`;
      invoices = invoices.filter((inv) => inv.billingPeriod.endsWith(monthStr));
    }

    // Set account name from first invoice (only if a specific account is selected)
    if (invoices.length > 0 && !selectedAccountName && selectedAccountId) {
      setSelectedAccountName(invoices[0].accountName);
    }

    // Sort by ID descending
    setFilteredInvoices(invoices.sort((a, b) => b.id - a.id));
  }, [allInvoices, selectedAccountId, selectedAccountName, selectedYear, selectedMonth]);

  /**
   * Handle filter change
   */
  const handleFilterChange = useCallback(() => {
    filterAndDisplayInvoices();
  }, [filterAndDisplayInvoices]);

  /**
   * Transform line items to drilldown structure
   */
  const transformToDrilldown = useCallback((lineItems: any[]): ServiceCost[] => {
    if (!lineItems || lineItems.length === 0) return [];

    const serviceMap = new Map<string, ServiceCost>();

    for (const item of lineItems) {
      // 1. Service Level
      if (!serviceMap.has(item.serviceName)) {
        serviceMap.set(item.serviceName, {
          serviceName: item.serviceName,
          totalCost: 0,
          regionCosts: [] as RegionCost[],
        });
      }
      const service = serviceMap.get(item.serviceName)!;
      const cost = typeof item.cost === 'string' ? parseFloat(item.cost) : item.cost;
      service.totalCost += cost;

      // 2. Region Level
      let regionCosts = service.regionCosts as RegionCost[];
      let region = regionCosts.find((r) => r.regionName === item.regionName);

      if (!region) {
        region = {
          regionName: item.regionName,
          cost: 0,
          resources: [],
        };
        regionCosts.push(region);
      }
      region.cost += cost;

      // 3. Resource Level
      region.resources.push({
        resourceId: `${item.serviceName}-${item.regionName}-${item.resourceName}-${item.cost}`,
        resourceName: item.resourceName,
        quantity: item.usageQuantity,
        unit: item.unit,
        cost: cost,
      } as ResourceCost);
    }

    // Convert Map to Array
    const result = Array.from(serviceMap.values());
    return result.sort((a, b) => b.totalCost - a.totalCost);
  }, []);

  /**
   * View invoice details
   */
  const viewInvoice = useCallback(
    (invoice: Invoice) => {
      setViewState('detail');
      setDetailLoading(true);
      setError(null);
      setSelectedInvoice(invoice);

      // Small timeout to allow UI transition
      setTimeout(() => {
        const breakdown = transformToDrilldown(invoice.lineItems);
        setDetailedBreakdown(breakdown);
        setDetailLoading(false);
      }, 50);
    },
    [transformToDrilldown]
  );

  /**
   * Go back to list
   */
  const goBackToList = useCallback(() => {
    setViewState('list');
    setSelectedInvoice(null);
    setError(null);
    filterAndDisplayInvoices();
  }, [filterAndDisplayInvoices]);

  /**
   * Download invoice PDF
   */
  const downloadInvoice = useCallback(async () => {
    if (!selectedInvoice) return;

    const id =
      selectedInvoice.awsAccountId !== 'N/A'
        ? selectedInvoice.awsAccountId
        : selectedInvoice.gcpProjectId !== 'N/A'
          ? selectedInvoice.gcpProjectId
          : selectedInvoice.azureSubscriptionId;

    try {
      const response = await apiFetch('/api/billops/invoices/download-preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(selectedInvoice),
      });

      if (!response.ok) throw new Error('PDF generation failed.');

      const blob = await response.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.style.display = 'none';
      a.href = url;
      a.download = `invoice-${id}-${selectedInvoice.billingPeriod}.pdf`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err) {
      console.error('Download error:', err);
      alert('Could not download the invoice PDF.');
    }
  }, [selectedInvoice]);

  /**
   * Toggle service expansion
   */
  const toggleService = useCallback((serviceName: string) => {
    setExpandedServices((prev) => ({
      ...prev,
      [serviceName]: !prev[serviceName],
    }));
  }, []);

  /**
   * Toggle region expansion
   */
  const toggleRegion = useCallback((serviceName: string, regionName: string) => {
    const key = `${serviceName}-${regionName}`;
    setExpandedRegions((prev) => ({
      ...prev,
      [key]: !prev[key],
    }));
  }, []);

  /**
   * Initialize on mount — fetch fresh data (no stale cache shown)
   */
  useEffect(() => {
    isMountedRef.current = true;

    if (accountsLoading) return;
    if (accounts.length === 0) {
      setListLoading(false);
      return () => { isMountedRef.current = false; };
    }

    fetchData(false);
    return () => {
      isMountedRef.current = false;
    };
  }, [accounts.length, accountsLoading]); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * React to sidebar account changes (same pattern as useBillOpsTickets)
   */
  useEffect(() => {
    setSelectedAccountName('');
    if (hasData) {
      // Data already loaded — re-filter client-side
      setListLoading(false);
    } else {
      fetchData(false);
    }
  }, [selectedAccountId]); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * Re-filter when filters or data change
   */
  useEffect(() => {
    if (allInvoices.length > 0) {
      filterAndDisplayInvoices();
    }
  }, [selectedAccountId, allInvoices, selectedYear, selectedMonth, filterAndDisplayInvoices]);

  /**
   * Initialize notification service
   */
  useEffect(() => {
    initNotificationService();
  }, []);

  /**
   * Poll for new invoices and notify users (every 60 seconds)
   */
  useEffect(() => {
    pollingIntervalRef.current = setInterval(async () => {
      try {
        const response = await apiFetch(`/api/admin/invoices?forceRefresh=false`, {
          credentials: 'include',
        });

        if (!response.ok) return;

        const data: Invoice[] = await response.json();
        const currentInvoiceIds = new Set(data.map(inv => inv.id));

        // Check for new finalized invoices (skip initial load)
        if (!isInitialLoadRef.current) {
          for (const invoice of data) {
            if (
              !previousInvoiceIdsRef.current.has(invoice.id) &&
              invoice.status === 'FINALIZED' &&
              document.hidden
            ) {
              // New finalized invoice detected - notify user
              notifyNewInvoice(
                invoice.accountName,
                invoice.billingPeriod,
                invoice.amount
              );
              break; // Only one notification for batch of new invoices
            }
          }
        }

        // Update tracking ref
        previousInvoiceIdsRef.current = currentInvoiceIds;
        isInitialLoadRef.current = false;

        // Update invoices state silently
        if (isMountedRef.current) {
          setAllInvoices(data);
        }
      } catch (err) {
        console.error('[Invoices] Polling error:', err);
      }
    }, 60000); // 60 second polling interval

    return () => {
      if (pollingIntervalRef.current) {
        clearInterval(pollingIntervalRef.current);
      }
    };
  }, []);

  /**
   * Update invoice IDs ref when invoices change
   */
  useEffect(() => {
    if (allInvoices.length > 0) {
      previousInvoiceIdsRef.current = new Set(allInvoices.map(inv => inv.id));
    }
  }, [allInvoices]);

  return {
    allInvoices,
    filteredInvoices,
    selectedInvoice,
    detailedBreakdown,
    expandedServices,
    expandedRegions,

    selectedYear,
    selectedMonth,
    years,
    months,

    selectedAccountId,
    selectedAccountName,

    viewState,
    listLoading,
    detailLoading,
    isUpdating,

    error,
    updateError,
    lastUpdated,
    lastUpdatedDisplay,

    hasData,

    setSelectedYear,
    setSelectedMonth,
    handleFilterChange,
    fetchData,
    viewInvoice,
    goBackToList,
    downloadInvoice,
    toggleService,
    toggleRegion,
  };
};

export default useInvoices;
