/**
 * BillOps Admin Invoices Types
 *
 * TypeScript interfaces for the BillOps Admin Invoices page.
 * Converted from: billops/admin_invoices.html
 */

export type InvoiceStatus = 'DRAFT' | 'FINALIZED' | 'VOID';

export interface AdminInvoiceItem {
  id: number;
  invoiceNumber: string;
  accountName: string;
  awsAccountId: string;
  gcpProjectId: string;
  azureSubscriptionId: string;
  cloudAccountId?: string;
  billingPeriod: string;
  amount: number;
  status: InvoiceStatus;
}

export interface CloudAccount {
  id: number;
  name: string;
  provider: 'AWS' | 'GCP' | 'Azure';
  identifier: string;
}

export interface GenerationParams {
  accountId: string;
  year: number;
  month: number;
}

export interface MergeOrder {
  target: AdminInvoiceItem;
  source: AdminInvoiceItem;
}

export interface AdminInvoicesState {
  invoices: AdminInvoiceItem[];
  accounts: CloudAccount[];
  loading: boolean;
  isUpdating: boolean;
  error: string | null;
  updateError: string | null;
  mergeSuccess: boolean;
  lastUpdated: number | null;
  lastUpdatedDisplay: string;
  selectedIds: string[];
  filterIds: string[];
  generation: GenerationParams;
  generating: boolean;
}

export interface UseBillOpsAdminInvoicesReturn {
  // State
  invoices: AdminInvoiceItem[];
  accounts: CloudAccount[];
  loading: boolean;
  isUpdating: boolean;
  error: string | null;
  updateError: string | null;
  mergeSuccess: boolean;
  lastUpdated: number | null;
  lastUpdatedDisplay: string;
  selectedIds: string[];
  filterIds: string[];
  generation: GenerationParams;
  generating: boolean;

  // Computed
  filteredInvoices: AdminInvoiceItem[];
  canMergeSelection: boolean;
  mergeButtonText: string;

  // Actions
  fetchData: (forceRefresh: boolean) => void;
  generateInvoice: () => Promise<void>;
  setSelectedIds: React.Dispatch<React.SetStateAction<string[]>>;
  setFilterIds: React.Dispatch<React.SetStateAction<string[]>>;
  setGeneration: React.Dispatch<React.SetStateAction<GenerationParams>>;
  requestMerge: () => void;
  cancelMerge: () => void;
  pendingMerge: boolean;
  mergeSelectedInvoices: () => Promise<void>;
  requestDelete: (invoiceId: number) => void;
  cancelDelete: () => void;
  confirmDelete: () => Promise<void>;
  pendingDelete: number | null;
  downloadPreview: (invoice: AdminInvoiceItem) => Promise<void>;
  getSelectedAccountName: () => string;
  isCloudFront: (invoice: AdminInvoiceItem) => boolean;
  determineMergeOrder: () => MergeOrder | null;
  dismissMergeSuccess: () => void;
  clearUpdateError: () => void;
}
