export interface MarketplaceSubscription {
  id: number;
  productCode: string;
  productTitle: string;
  vendorName: string | null;
  subscriptionStatus: 'ACTIVE' | 'INACTIVE' | 'EXPIRED';
  expiryDate: string | null;
  totalCostUsd: number;
  syncedAt: string;
}

export interface MeterBillingMonth {
  yearMonth: string;
  usageType: string;
  usageQuantity: number;
  costUsd: number;
}

export interface MarketOpsSyncResult {
  status: string;
  cloudAccountId: string;
  subscriptionsFound: number;
  billingMonthsSynced: number;
  syncedAt: string;
}
