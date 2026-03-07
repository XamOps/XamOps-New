/**
 * useBillOpsAdminInvoices Hook
 *
 * Converted from: billops/admin_invoices.html (adminInvoiceList Alpine.js component)
 *
 * Manages BillOps admin invoices data with:
 * - LocalStorage caching for instant load
 * - Background refresh capability
 * - Manual refresh capability
 * - Invoice generation
 * - Invoice merging
 * - PDF preview download
 *
 * What stayed the same:
 * - Same API endpoints
 * - Same caching strategy (localStorage)
 * - Same forceRefresh parameter logic
 * - Same merge validation rules
 */

import { useState, useEffect, useCallback, useMemo, useRef } from 'react';
import { apiFetch } from '../utils/apiClient';
import type {
  AdminInvoiceItem,
  CloudAccount,
  GenerationParams,
  MergeOrder,
  UseBillOpsAdminInvoicesReturn
} from '../types/billopsAdminInvoices';

const CACHE_KEY = 'admin_invoices_list';

export const useBillOpsAdminInvoices = (): UseBillOpsAdminInvoicesReturn => {
  // Core state
  const [invoices, setInvoices] = useState<AdminInvoiceItem[]>([]);
  const [accounts, setAccounts] = useState<CloudAccount[]>([]);
  const [loading, setLoading] = useState(true);
  const [isUpdating, setIsUpdating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [updateError, setUpdateError] = useState<string | null>(null);
  const [mergeSuccess, setMergeSuccess] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<number | null>(null);
  const [lastUpdatedDisplay, setLastUpdatedDisplay] = useState('');

  // Selection state
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [filterIds, setFilterIds] = useState<string[]>([]);

  // Generation state
  const [generation, setGeneration] = useState<GenerationParams>({
    accountId: '',
    year: new Date().getFullYear(),
    month: new Date().getMonth() + 1
  });
  const [generating, setGenerating] = useState(false);
  const [pendingMerge, setPendingMerge] = useState(false);

  const isMountedRef = useRef(true);

  /**
   * Update last updated display string
   */
  const updateLastUpdatedDisplay = useCallback((timestamp: number) => {
    const date = new Date(timestamp);
    setLastUpdatedDisplay(date.toLocaleString());
  }, []);

  /**
   * Load invoices from cache
   */
  const loadFromCache = useCallback(() => {
    const cached = localStorage.getItem(CACHE_KEY);
    if (cached) {
      try {
        const { data, timestamp } = JSON.parse(cached);
        if (data && Array.isArray(data)) {
          console.log('⚡ [Admin Invoices] Loaded from cache.');
          setInvoices(data);
          setLastUpdated(timestamp);
          updateLastUpdatedDisplay(timestamp);
          setLoading(false);
          return true;
        }
      } catch (e) {
        console.warn('Failed to parse cached admin invoices', e);
      }
    }
    return false;
  }, [updateLastUpdatedDisplay]);

  /**
   * Fetch accounts for dropdown
   */
  const fetchAccounts = useCallback(async () => {
    try {
      const response = await apiFetch('/api/accounts', { credentials: 'include' });
      if (!response.ok) return;

      const data = await response.json();
      const mappedAccounts: CloudAccount[] = data.map((a: {
        id: number;
        accountName: string;
        provider: 'AWS' | 'GCP' | 'Azure';
        awsAccountId?: string;
        gcpProjectId?: string;
        azureSubscriptionId?: string;
      }) => ({
        id: a.id,
        name: a.accountName,
        provider: a.provider,
        identifier: a.provider === 'AWS' ? a.awsAccountId :
          (a.provider === 'GCP' ? a.gcpProjectId : a.azureSubscriptionId)
      }));

      if (isMountedRef.current) {
        setAccounts(mappedAccounts);
      }
    } catch (err) {
      console.warn('Failed to load accounts list', err);
    }
  }, []);

  /**
   * Sync selection from sidebar/URL
   */
  const syncSelectionFromSidebar = useCallback((accountList: CloudAccount[], ids: string[]) => {
    if (ids.length > 0) {
      const selectedId = ids[0];
      const account = accountList.find(a =>
        String(a.id) === String(selectedId) ||
        String(a.identifier) === String(selectedId)
      );
      if (account) {
        setGeneration(prev => ({ ...prev, accountId: account.identifier }));
      }
    }
  }, []);

  /**
   * Fetch invoices data from API
   */
  const fetchData = useCallback(async (forceRefresh: boolean) => {
    if (forceRefresh || invoices.length === 0) {
      setIsUpdating(true);
      setUpdateError(null);
      if (invoices.length === 0) setLoading(true);
    } else {
      setIsUpdating(true);
    }

    // Clear selection on refresh to avoid stale data
    setSelectedIds([]);

    try {
      const response = await apiFetch(`/api/admin/invoices?forceRefresh=${forceRefresh}`, {
        credentials: 'include',
      });

      if (!response.ok) {
        if (response.status === 401 || response.status === 403) {
          window.location.href = '/';
          return;
        }
        throw new Error(`Server responded with status: ${response.status}`);
      }

      const data = await response.json();
      console.log('✅ [Admin Invoices] Fresh data received.');

      if (isMountedRef.current) {
        setInvoices(data);

        // Cache logic
        const now = Date.now();
        setLastUpdated(now);
        updateLastUpdatedDisplay(now);
        localStorage.setItem(CACHE_KEY, JSON.stringify({
          data: data,
          timestamp: now
        }));

        setError(null);
        setUpdateError(null);
      }
    } catch (err) {
      console.error('Error fetching invoices:', err);
      const msg = err instanceof Error ? err.message : 'Failed to load invoices.';

      if (isMountedRef.current) {
        if (forceRefresh || invoices.length === 0) {
          setError(msg);
        } else {
          setUpdateError(msg + ' (Showing cached data)');
        }
      }
    } finally {
      if (isMountedRef.current) {
        setLoading(false);
        setIsUpdating(false);
      }
    }
  }, [invoices.length, updateLastUpdatedDisplay]);

  /**
   * Generate a new draft invoice
   */
  const generateInvoice = useCallback(async () => {
    if (!generation.accountId) {
      alert('No account selected. Please select an account from the sidebar.');
      return;
    }

    setGenerating(true);

    try {
      const response = await apiFetch('/api/admin/invoices/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(generation),
        credentials: 'include',
      });

      if (!response.ok) {
        throw new Error('Failed to generate invoice.');
      }

      // Refresh the list
      await fetchData(true);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to generate invoice.';
      alert(msg);
    } finally {
      if (isMountedRef.current) {
        setGenerating(false);
      }
    }
  }, [generation, fetchData]);

  /**
   * Check if invoice is CloudFront type
   */
  const isCloudFront = useCallback((invoice: AdminInvoiceItem): boolean => {
    return !!(invoice.invoiceNumber && invoice.invoiceNumber.startsWith('CF-'));
  }, []);

  /**
   * Get account ID from invoice (works across AWS/GCP/Azure)
   */
  const getInvoiceAccountId = useCallback((invoice: AdminInvoiceItem): string => {
    if (invoice.awsAccountId && invoice.awsAccountId !== 'N/A') {
      return invoice.awsAccountId;
    }
    if (invoice.gcpProjectId && invoice.gcpProjectId !== 'N/A') {
      return invoice.gcpProjectId;
    }
    return invoice.azureSubscriptionId || '';
  }, []);

  /**
   * Filtered invoices based on selected account
   */
  const filteredInvoices = useMemo(() => {
    let result = invoices;

    if (filterIds.length > 0) {
      result = result.filter(inv =>
        filterIds.some(id =>
          String(id) === String(inv.cloudAccountId) ||
          (inv.awsAccountId && String(id) === String(inv.awsAccountId)) ||
          (inv.gcpProjectId && String(id) === String(inv.gcpProjectId)) ||
          (inv.azureSubscriptionId && String(id) === String(inv.azureSubscriptionId))
        )
      );
    }

    return result.sort((a, b) => b.id - a.id);
  }, [invoices, filterIds]);

  /**
   * Check if current selection can be merged
   */
  const canMergeSelection = useMemo(() => {
    if (selectedIds.length !== 2) return false;

    const inv1 = invoices.find(i => String(i.id) === String(selectedIds[0]));
    const inv2 = invoices.find(i => String(i.id) === String(selectedIds[1]));

    if (!inv1 || !inv2) return false;

    // Validation Rules
    const id1 = getInvoiceAccountId(inv1);
    const id2 = getInvoiceAccountId(inv2);

    const sameAccount = id1 === id2;
    const samePeriod = inv1.billingPeriod === inv2.billingPeriod;
    const bothDraft = inv1.status === 'DRAFT' && inv2.status === 'DRAFT';

    return sameAccount && samePeriod && bothDraft;
  }, [selectedIds, invoices, getInvoiceAccountId]);

  /**
   * Determine merge order (Standard is target, CloudFront is source)
   */
  const determineMergeOrder = useCallback((): MergeOrder | null => {
    if (selectedIds.length !== 2) return null;

    const inv1 = invoices.find(i => String(i.id) === String(selectedIds[0]));
    const inv2 = invoices.find(i => String(i.id) === String(selectedIds[1]));

    if (!inv1 || !inv2) return null;

    let target: AdminInvoiceItem;
    let source: AdminInvoiceItem;

    // Priority: Standard Invoice is Target. CloudFront is Source.
    if (!isCloudFront(inv1) && isCloudFront(inv2)) {
      target = inv1;
      source = inv2;
    } else if (isCloudFront(inv1) && !isCloudFront(inv2)) {
      target = inv2;
      source = inv1;
    } else {
      // Fallback: Lower ID (older) is target
      if (inv1.id < inv2.id) {
        target = inv1;
        source = inv2;
      } else {
        target = inv2;
        source = inv1;
      }
    }

    return { target, source };
  }, [selectedIds, invoices, isCloudFront]);

  /**
   * Merge button text
   */
  const mergeButtonText = useMemo(() => {
    if (selectedIds.length !== 2) return 'Select 2 Invoices';
    if (!canMergeSelection) return 'Incompatible Selection';

    const order = determineMergeOrder();
    if (!order) return 'Merge Selected';

    return `Merge ${order.source.invoiceNumber} → ${order.target.invoiceNumber}`;
  }, [selectedIds.length, canMergeSelection, determineMergeOrder]);

  /**
   * Format currency helper
   */
  const formatCurrency = useCallback((value: number): string => {
    return new Intl.NumberFormat('en-US', {
      style: 'currency',
      currency: 'USD',
      minimumFractionDigits: 2,
      maximumFractionDigits: 4
    }).format(value || 0);
  }, []);

  /**
   * Merge selected invoices — opens confirmation dialog
   */
  const requestMerge = useCallback(() => {
    if (!canMergeSelection) return;
    const order = determineMergeOrder();
    if (!order) return;
    setPendingMerge(true);
  }, [canMergeSelection, determineMergeOrder]);

  const cancelMerge = useCallback(() => setPendingMerge(false), []);

  const mergeSelectedInvoices = useCallback(async () => {
    if (!canMergeSelection) return;
    setPendingMerge(false);

    const order = determineMergeOrder();
    if (!order) return;

    setIsUpdating(true);

    try {
      const response = await apiFetch(
        `/api/admin/invoices/merge?targetInvoiceId=${target.id}&sourceInvoiceId=${source.id}`,
        {
          method: 'POST',
          credentials: 'include',
        }
      );

      if (!response.ok) {
        throw new Error('Merge failed');
      }

      if (isMountedRef.current) {
        setMergeSuccess(true);
        setSelectedIds([]);
        setTimeout(() => {
          if (isMountedRef.current) {
            setMergeSuccess(false);
          }
        }, 4000);
        fetchData(true);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Unknown error';
      alert('Merge failed: ' + msg);
      if (isMountedRef.current) {
        setIsUpdating(false);
      }
    }
  }, [canMergeSelection, determineMergeOrder, formatCurrency, fetchData]);

  /**
   * Download invoice preview PDF
   */
  const downloadPreview = useCallback(async (invoice: AdminInvoiceItem) => {
    try {
      // First fetch full invoice details
      const detailResponse = await apiFetch(`/api/admin/invoices/${invoice.id}`, {
        credentials: 'include',
      });

      if (!detailResponse.ok) {
        throw new Error('Failed to fetch invoice details');
      }

      const fullDto = await detailResponse.json();

      // Then request preview PDF
      const pdfResponse = await apiFetch('/api/billops/invoices/download-preview', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(fullDto),
        credentials: 'include',
      });

      if (!pdfResponse.ok) {
        throw new Error('Failed to generate preview');
      }

      const blob = await pdfResponse.blob();
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.style.display = 'none';
      a.href = url;
      a.download = `preview-${invoice.invoiceNumber}.pdf`;
      document.body.appendChild(a);
      a.click();
      window.URL.revokeObjectURL(url);
      document.body.removeChild(a);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Unknown error';
      alert('Preview failed: ' + msg);
    }
  }, []);

  /**
   * Get selected account name for display
   */
  const getSelectedAccountName = useCallback((): string => {
    if (!generation.accountId) return 'No Account Selected';
    const acc = accounts.find(a => a.identifier === generation.accountId);
    return acc ? `${acc.name} (${acc.identifier})` : generation.accountId;
  }, [generation.accountId, accounts]);

  /**
   * Dismiss merge success notification
   */
  const dismissMergeSuccess = useCallback(() => {
    setMergeSuccess(false);
  }, []);

  /**
   * Clear update error
   */
  const clearUpdateError = useCallback(() => {
    setUpdateError(null);
  }, []);

  /**
   * Initialize on mount
   */
  useEffect(() => {
    isMountedRef.current = true;

    // Get filter IDs from URL or session storage
    const urlParams = new URLSearchParams(window.location.search);
    const urlIds = urlParams.get('accountIds') || urlParams.get('accountId');

    let initialFilterIds: string[] = [];
    if (urlIds) {
      initialFilterIds = urlIds.split(',');
    } else {
      const storageIds = sessionStorage.getItem('selectedAccountIds');
      if (storageIds) {
        try {
          initialFilterIds = JSON.parse(storageIds);
        } catch (e) {
          console.warn('Failed to parse selectedAccountIds from session storage');
        }
      }
    }

    setFilterIds(initialFilterIds);

    if (initialFilterIds.length > 0) {
      setGeneration(prev => ({ ...prev, accountId: initialFilterIds[0] }));
    }

    // Fetch accounts
    fetchAccounts().then(() => {
      // After accounts load, sync selection from sidebar
      // We'll need to wait for accounts to be in state
    });

    // Load from cache first
    loadFromCache();

    // Then background refresh
    fetchData(false);

    return () => {
      isMountedRef.current = false;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  /**
   * Sync account selection when accounts and filterIds change
   */
  useEffect(() => {
    if (accounts.length > 0 && filterIds.length > 0) {
      syncSelectionFromSidebar(accounts, filterIds);
    }
  }, [accounts, filterIds, syncSelectionFromSidebar]);

  return {
    // State
    invoices,
    accounts,
    loading,
    isUpdating,
    error,
    updateError,
    mergeSuccess,
    lastUpdated,
    lastUpdatedDisplay,
    selectedIds,
    filterIds,
    generation,
    generating,

    // Computed
    filteredInvoices,
    canMergeSelection,
    mergeButtonText,

    // Actions
    fetchData,
    generateInvoice,
    setSelectedIds,
    setGeneration,
    requestMerge,
    cancelMerge,
    pendingMerge,
    mergeSelectedInvoices,
    downloadPreview,
    getSelectedAccountName,
    isCloudFront,
    determineMergeOrder,
    dismissMergeSuccess,
    clearUpdateError,
  };
};

export default useBillOpsAdminInvoices;
