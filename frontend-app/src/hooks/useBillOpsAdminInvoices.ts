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
  ClientOption,
  CloudAccount,
  CurUnavailableNotice,
  GenerationParams,
  InvoiceDataSource,
  MergeOrder,
  UploadDraftParams,
  UploadDraftPreview,
  UploadResult,
  UseBillOpsAdminInvoicesReturn
} from '../types/billopsAdminInvoices';

const CACHE_KEY = 'admin_invoices_list';

/**
 * Deadline for generating a draft.
 *
 * The first draft for a month reads it from the Cost and Usage Report — two Athena
 * queries, each allowed up to 60s server-side. The 45s default would give up while
 * the server carried on and saved the draft anyway, and the retry would then create a
 * second one.
 */
const GENERATE_TIMEOUT_MS = 150_000;

/** Uploading and parsing a multi-page bill PDF takes longer than a JSON round trip. */
const UPLOAD_TIMEOUT_MS = 120_000;

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
    month: new Date().getMonth() + 1,
    includeIrn: false,
  });
  const [generating, setGenerating] = useState(false);
  const [curUnavailable, setCurUnavailable] = useState<CurUnavailableNotice | null>(null);
  const [pendingMerge, setPendingMerge] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<number | null>(null);

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
      const response = await apiFetch('/api/billops/support/accounts', { credentials: 'include' });
      if (!response.ok) return;

      const data = await response.json();
      const mappedAccounts: CloudAccount[] = data.map((a: {
        id: number;
        accountName: string;
        provider: string;
        accountId: string;
      }) => ({
        id: a.id,
        name: a.accountName,
        provider: a.provider as 'AWS' | 'GCP' | 'Azure',
        identifier: a.accountId,
      }));

      if (isMountedRef.current) {
        setAccounts(mappedAccounts);
      }
    } catch (err) {
      console.warn('Failed to load accounts list', err);
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

  // A refusal describes one account and month. Once the form moves on it no longer
  // applies, and leaving it up would offer a Cost Explorer fallback for a request the
  // admin is no longer making.
  useEffect(() => {
    setCurUnavailable(null);
  }, [generation]);

  /**
   * Posts a draft request against one source.
   *
   * A 409 CUR_UNAVAILABLE is not an error: the report could not answer and the server
   * made no Cost Explorer call. It becomes a notice the page renders with the reason
   * and a way to fall back, instead of an alert that leaves the admin nowhere to go.
   */
  const submitDraft = useCallback(async (
    params: GenerationParams,
    source: InvoiceDataSource,
  ): Promise<number | null> => {
    setGenerating(true);

    try {
      const response = await apiFetch('/api/admin/invoices/generate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...params, source }),
        credentials: 'include',
        timeoutMs: GENERATE_TIMEOUT_MS,
      });

      if (response.status === 409) {
        const body = await response.json().catch(() => null);
        if (body?.error === 'CUR_UNAVAILABLE') {
          if (isMountedRef.current) {
            setCurUnavailable({
              reason: String(body.reason ?? ''),
              message: String(body.message ?? 'The Cost and Usage Report could not build this invoice.'),
              canUseCostExplorer: body.canUseCostExplorer !== false,
              params,
            });
          }
          return null;
        }
      }

      if (!response.ok) {
        throw new Error('Failed to generate invoice.');
      }

      const created = await response.json().catch(() => null);
      if (isMountedRef.current) {
        setCurUnavailable(null);
      }
      await fetchData(true);
      return typeof created?.id === 'number' ? created.id : null;
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to generate invoice.';
      alert(msg);
      return null;
    } finally {
      if (isMountedRef.current) {
        setGenerating(false);
      }
    }
  }, [fetchData]);

  /**
   * Generate a new draft invoice from the Cost and Usage Report.
   */
  const generateInvoice = useCallback(async (): Promise<number | null> => {
    if (!generation.accountId) {
      alert('No account selected. Please select an account from the sidebar.');
      return null;
    }
    return submitDraft(generation, 'CUR');
  }, [generation, submitDraft]);

  /**
   * Repeats the request the report refused, against Cost Explorer.
   *
   * Uses the params stored with the refusal rather than the current form, so the
   * fallback is always for the account and month the admin was just told about.
   */
  const generateFromCostExplorer = useCallback(async (): Promise<number | null> => {
    if (!curUnavailable || !curUnavailable.canUseCostExplorer) {
      return null;
    }
    return submitDraft(curUnavailable.params, 'COST_EXPLORER');
  }, [curUnavailable, submitDraft]);

  const dismissCurUnavailable = useCallback(() => setCurUnavailable(null), []);

  /** The server's own message for a refused upload, or a fallback. */
  const uploadError = async (response: Response, fallback: string): Promise<string> => {
    if (response.status === 403) return 'You don’t have permission to upload drafts.';
    if (response.status === 404 || response.status === 405) {
      return 'The BillOps server doesn’t have draft upload yet. Restart or redeploy billops and try again.';
    }
    if (response.status === 413) return 'That file is too large to upload.';
    const body = await response.json().catch(() => null);
    // Messages from the upload endpoints are written to be shown as-is. Anything else
    // (Spring's default error body has an empty message) gets the status, so the
    // admin isn't told the PDF is bad when the server is the problem.
    return typeof body?.message === 'string' && body.message
      ? body.message
      : `${fallback} (server responded ${response.status})`;
  };

  /**
   * Reads an AWS bill PDF on the server and returns what it found. Nothing is saved;
   * the admin confirms the account and period before {@link createDraftFromUpload}.
   */
  const previewUploadedDraft = useCallback(async (file: File): Promise<UploadResult<UploadDraftPreview>> => {
    const form = new FormData();
    form.append('file', file);
    try {
      const response = await apiFetch('/api/admin/invoices/upload-draft/preview', {
        method: 'POST',
        body: form,
        credentials: 'include',
        timeoutMs: UPLOAD_TIMEOUT_MS,
      });
      if (!response.ok) {
        return { ok: false, message: await uploadError(response, 'The PDF couldn’t be read.') };
      }
      return { ok: true, value: await response.json() };
    } catch (err) {
      return { ok: false, message: err instanceof Error ? err.message : 'The PDF couldn’t be uploaded.' };
    }
  }, []);

  /**
   * Creates a draft from the same PDF with the account and period the admin confirmed.
   * The server re-reads the file, so what is saved never depends on the preview.
   */
  const createDraftFromUpload = useCallback(async (
    file: File,
    params: UploadDraftParams,
  ): Promise<UploadResult<number>> => {
    const form = new FormData();
    form.append('file', file);
    form.append('accountId', params.accountId);
    form.append('year', String(params.year));
    form.append('month', String(params.month));
    form.append('includeIrn', String(params.includeIrn));
    if (params.newAccount) {
      form.append('accountName', params.newAccount.accountName);
      if (params.newAccount.clientId != null) form.append('clientId', String(params.newAccount.clientId));
      else form.append('clientName', params.newAccount.clientName);
    }
    try {
      const response = await apiFetch('/api/admin/invoices/upload-draft', {
        method: 'POST',
        body: form,
        credentials: 'include',
        timeoutMs: UPLOAD_TIMEOUT_MS,
      });
      if (!response.ok) {
        return { ok: false, message: await uploadError(response, 'The draft couldn’t be created.') };
      }
      const created = await response.json().catch(() => null);
      // An account the bill introduced is now in BillOps; refresh the pickers too.
      await Promise.all([fetchData(true), params.newAccount ? fetchAccounts() : Promise.resolve()]);
      return typeof created?.id === 'number'
        ? { ok: true, value: created.id }
        : { ok: false, message: 'The draft was created but its id wasn’t returned. Refresh the list.' };
    } catch (err) {
      return { ok: false, message: err instanceof Error ? err.message : 'The draft couldn’t be created.' };
    }
  }, [fetchData, fetchAccounts]);

  /** Clients a new account from an uploaded bill can be added under. */
  const fetchClients = useCallback(async (): Promise<ClientOption[]> => {
    try {
      const response = await apiFetch('/api/billops/clients', { credentials: 'include' });
      if (!response.ok) return [];
      const data = await response.json();
      return Array.isArray(data)
        ? data.map((c: { id: number; name: string }) => ({ id: c.id, name: c.name }))
            .sort((a: ClientOption, b: ClientOption) => a.name.localeCompare(b.name))
        : [];
    } catch {
      return [];
    }
  }, []);

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

    const { target, source } = order;

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
   * Delete a DRAFT invoice — opens confirmation dialog
   */
  const requestDelete = useCallback((invoiceId: number) => {
    setPendingDelete(invoiceId);
  }, []);

  const cancelDelete = useCallback(() => setPendingDelete(null), []);

  const confirmDelete = useCallback(async () => {
    if (!pendingDelete) return;
    const id = pendingDelete;
    setPendingDelete(null);
    setIsUpdating(true);
    try {
      const res = await apiFetch(`/api/admin/invoices/${id}`, {
        method: 'DELETE',
        credentials: 'include',
      });
      if (!res.ok) throw new Error('Delete failed');
      if (isMountedRef.current) {
        await fetchData(true);
      }
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Unknown error';
      alert('Delete failed: ' + msg);
      if (isMountedRef.current) {
        setIsUpdating(false);
      }
    }
  }, [pendingDelete, fetchData]);

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

    // Only apply filter from URL params (for deep linking) — never from sessionStorage
    // Admin invoices page shows ALL invoices by default; filtering is done via UI controls
    const urlParams = new URLSearchParams(window.location.search);
    const urlIds = urlParams.get('accountIds') || urlParams.get('accountId');

    if (urlIds) {
      const initialFilterIds = urlIds.split(',');
      setFilterIds(initialFilterIds);
      setGeneration(prev => ({ ...prev, accountId: initialFilterIds[0] }));
    }

    // Fetch accounts for the filter/generation dropdown
    fetchAccounts();

    // Load from cache first
    loadFromCache();

    // Then background refresh
    fetchData(false);

    return () => {
      isMountedRef.current = false;
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

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
    curUnavailable,

    // Computed
    filteredInvoices,
    canMergeSelection,
    mergeButtonText,

    // Actions
    fetchData,
    generateInvoice,
    generateFromCostExplorer,
    dismissCurUnavailable,
    previewUploadedDraft,
    createDraftFromUpload,
    fetchClients,
    setSelectedIds,
    setFilterIds,
    setGeneration,
    requestMerge,
    cancelMerge,
    pendingMerge,
    mergeSelectedInvoices,
    requestDelete,
    cancelDelete,
    confirmDelete,
    pendingDelete,
    downloadPreview,
    getSelectedAccountName,
    isCloudFront,
    determineMergeOrder,
    dismissMergeSuccess,
    clearUpdateError,
  };
};

export default useBillOpsAdminInvoices;
