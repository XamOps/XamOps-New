export interface MarketplaceSubscription {
  id: number;
  productCode: string;
  productTitle: string;
  vendorName: string | null;
  subscriptionStatus: 'ACTIVE' | 'INACTIVE' | 'EXPIRED';
  expiryDate: string | null;
  totalCostUsd: number;
  /** Present when AWS CE cannot attribute costs to individual products (multiple subscriptions).
   *  Represents the total account-level marketplace spend. */
  accountTotalCostUsd?: number;
  syncedAt: string;
}

/** Wrapper returned by the monthly billing endpoint */
export interface MeterBillingResponse {
  entries: MeterBillingMonth[];
  /** true when AWS CE billing could not be attributed to this specific product and
   *  account-level marketplace billing is shown as a fallback */
  accountLevel: boolean;
}

export interface MeterBillingMonth {
  yearMonth: string;
  usageType: string;
  usageQuantity: number;
  costUsd: number;
}

export interface DailyMeterBilling {
  date: string;           // "2026-03-01"
  costUsd: number;
  usageQuantity: number;
}

/** Wrapper returned by the daily billing endpoint */
export interface DailyMeterBillingResponse {
  entries: DailyMeterBilling[];
  /** true when AWS CE billing could not be attributed to this specific product and
   *  account-level marketplace billing is shown as a fallback */
  accountLevel: boolean;
}

export interface MarketOpsSyncResult {
  status: string;
  cloudAccountId: string;
  subscriptionsFound: number;
  billingMonthsSynced: number;
  syncedAt: string;
}
