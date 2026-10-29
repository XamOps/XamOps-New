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
  /** File name of the AWS bill PDF the draft was created from; null when generated. */
  uploadedFileName?: string | null;
}

/** What the server read from an uploaded AWS bill PDF, before any draft exists. */
export interface UploadDraftPreview {
  fileName: string;
  pageCount: number;
  /** The 12-digit account ID printed on the bill, if one was found. */
  accountId: string | null;
  /** The BillOps account that ID resolved to; null when not set up or not accessible. */
  account: { id: number; name: string; identifier: string; provider: string } | null;
  /** The bill's account isn't in BillOps; creating the draft adds it. */
  accountWillBeCreated: boolean;
  suggestedAccountName: string | null;
  billingPeriod: string | null;
  year: number | null;
  month: number | null;
  datePrinted: string | null;
  estimated: boolean;
  grandTotal: number | null;
  preTaxTotal: number | null;
  taxTotal: number | null;
  marketplaceTotal: number | null;
  lineItemTotal: number;
  lineItemCount: number;
  /** Lines added so every subtotal on the draft matches the bill's printed one. */
  roundingLineCount: number;
  roundingTotal: number;
  services: { serviceName: string; total: number; lineCount: number }[];
  warnings: string[];
}

/** The admin's confirmed choices for a draft built from an uploaded bill. */
export interface UploadDraftParams {
  /** An existing account's identifier, or the bill's own account ID to add it. */
  accountId: string;
  year: number;
  month: number;
  includeIrn: boolean;
  /** Only used when the account is being added: its name, and the client it goes under. */
  newAccount?: { accountName: string; clientId: number | null; clientName: string };
}

export interface ClientOption {
  id: number;
  name: string;
}

/** A call that either succeeded or carries a message to show as-is. */
export type UploadResult<T> = { ok: true; value: T } | { ok: false; message: string };

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
  /** Reads an AWS bill PDF and reports what a draft from it would hold. Creates nothing. */
  previewUploadedDraft: (file: File) => Promise<UploadResult<UploadDraftPreview>>;
  /** Creates a draft from an AWS bill PDF. Resolves to the new invoice's id. */
  createDraftFromUpload: (file: File, params: UploadDraftParams) => Promise<UploadResult<number>>;
  /** Clients a new account from an uploaded bill can be added under. */
  fetchClients: () => Promise<ClientOption[]>;
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
