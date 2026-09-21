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
  /** Where the figures came from; null on invoices generated before this was recorded. */
  dataSource?: InvoiceDataSource | null;
  /** Built from a month that had not closed — an estimate of the bill, not the bill. */
  provisional?: boolean;
}

/** CUR is the customer's Cost and Usage Report; COST_EXPLORER only ever on an admin's request. */
export type InvoiceDataSource = 'CUR' | 'COST_EXPLORER';

/**
 * Why the Cost and Usage Report could not build a draft, from the generate endpoint's 409.
 *
 * Carries the exact request that was refused, so falling back to Cost Explorer repeats
 * that request rather than whatever the form happens to say by the time the admin clicks.
 */
export interface CurUnavailableNotice {
  reason: string;
  message: string;
  canUseCostExplorer: boolean;
  params: GenerationParams;
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
  includeIrn: boolean;
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
  /** Set when the report refused the last draft; cleared by any change to the form. */
  curUnavailable: CurUnavailableNotice | null;

  // Computed
  filteredInvoices: AdminInvoiceItem[];
  canMergeSelection: boolean;
  mergeButtonText: string;

  // Actions
  fetchData: (forceRefresh: boolean) => void;
  /** Builds a draft from the report. Resolves to the new invoice's id, or null if none was made. */
  generateInvoice: () => Promise<number | null>;
  /** Repeats the refused request against Cost Explorer. The only path that reaches it. */
  generateFromCostExplorer: () => Promise<number | null>;
  dismissCurUnavailable: () => void;
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
