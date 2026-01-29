/**
 * TypeScript types for Invoices
 *
 * Converted from: billops/invoices.html
 *
 * Defines all data structures for invoice list, details, and line items
 */

export interface InvoiceDiscount {
  id: number;
  description: string;
  serviceName: string;
  percentage: number;
}

export interface InvoiceLineItem {
  id: number;
  serviceName: string;
  regionName: string;
  resourceName: string;
  usageQuantity: number | null;
  unit: string | null;
  cost: number;
}

export interface Invoice {
  id: number;
  invoiceNumber: string;
  awsAccountId: string;
  gcpProjectId: string;
  azureSubscriptionId: string;
  accountName: string;
  billingPeriod: string;
  invoiceDate: string;
  amount: number;
  preDiscountTotal: number;
  discountAmount: number;
  taxAmount: number;
  status: 'DRAFT' | 'FINALIZED' | 'PAID' | 'OVERDUE';
  lineItems: InvoiceLineItem[];
  discounts: InvoiceDiscount[];
}

export interface RegionCost {
  regionName: string;
  cost: number;
  resources: ResourceCost[];
}

export interface ServiceCost {
  serviceName: string;
  totalCost: number;
  regionCosts: RegionCost[];
}

export interface ResourceCost {
  resourceId: string;
  resourceName: string;
  quantity: number | null;
  unit: string | null;
  cost: number;
}

export interface Month {
  name: string;
  value: number;
}

export interface UseInvoicesReturn {
  allInvoices: Invoice[];
  filteredInvoices: Invoice[];
  selectedInvoice: Invoice | null;
  detailedBreakdown: ServiceCost[];
  expandedServices: Record<string, boolean>;
  expandedRegions: Record<string, boolean>;

  selectedYear: string;
  selectedMonth: string;
  years: number[];
  months: Month[];

  selectedAccountId: string | null;
  selectedAccountName: string;

  viewState: 'list' | 'detail';
  listLoading: boolean;
  detailLoading: boolean;
  isUpdating: boolean;

  error: string | null;
  updateError: string | null;
  lastUpdated: number | null;
  lastUpdatedDisplay: string;

  hasData: boolean;

  setSelectedYear: (year: string) => void;
  setSelectedMonth: (month: string) => void;
  handleFilterChange: () => void;
  fetchData: (forceRefresh: boolean) => void;
  viewInvoice: (invoice: Invoice) => void;
  goBackToList: () => void;
  downloadInvoice: () => void;
  toggleService: (serviceName: string) => void;
  toggleRegion: (serviceName: string, regionName: string) => void;
}
