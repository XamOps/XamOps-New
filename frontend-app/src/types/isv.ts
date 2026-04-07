// ─── ISV Product Types ────────────────────────────────────────────────────────

export interface IsvProductDimension {
  id: number;
  dimensionKey: string;
  description: string | null;
  unit: string | null;
}

export interface IsvProduct {
  id: number;
  productCode: string;
  productName: string;
  description: string | null;
  ownerEmail: string;
  roleArn: string;
  externalId: string | null;
  apiKey: string;
  status: 'ACTIVE' | 'INACTIVE';
  createdAt: string;
  updatedAt: string | null;
  dimensions: IsvProductDimension[];
  // Live stats
  activeCustomers: number;
  totalCustomers: number;
  pendingEvents: number;
  failedEvents: number;
  failedMeteringRecords: number;
  totalMeteringRecords: number;
}

// ─── Customer Types ───────────────────────────────────────────────────────────

export interface IsvCustomer {
  id: number;
  customerIdentifier: string;
  customerAwsAccountId: string | null;
  status: 'ACTIVE' | 'CANCELLED' | 'EXPIRED';
  registeredAt: string;
  cancelledAt: string | null;
  lastEntitlementCheck: string | null;
}

// ─── Metering Record Types ────────────────────────────────────────────────────

export interface MeteringRecord {
  id: number;
  customerIdentifier: string;
  dimension: string;
  quantity: number;
  usageTimestamp: string;
  awsMeteringRecordId: string | null;
  status: 'SUBMITTED' | 'SUCCESS' | 'FAILED';
  submittedAt: string;
  errorMessage: string | null;
}

// ─── SNS Subscription Event Types ────────────────────────────────────────────

export interface SubscriptionEvent {
  id: number;
  productCode: string;
  customerIdentifier: string | null;
  eventType:
    | 'subscribe-success'
    | 'subscribe-fail'
    | 'unsubscribe-pending'
    | 'unsubscribe-success'
    | 'entitlement-updated'
    | 'subscription-confirmation'
    | string;
  status: 'PROCESSED' | 'FAILED' | 'IGNORED';
  errorMessage: string | null;
  receivedAt: string;
  processedAt: string | null;
}

// ─── Request Types ────────────────────────────────────────────────────────────

export interface IsvProductRequest {
  productCode: string;
  productName: string;
  description?: string;
  ownerEmail: string;
  roleArn: string;
  externalId?: string;
  dimensions: { dimensionKey: string; description?: string; unit?: string }[];
}

export interface UsageEventRequest {
  customerId: string;
  dimension: string;
  quantity: number;
  timestamp?: string;
}

// ─── Buyer Usage Types ────────────────────────────────────────────────────────

export interface BuyerDimensionUsage {
  dimension: string;
  totalQuantity: number;
}

export interface BuyerSubscription {
  productCode: string;
  productName: string;
  customerIdentifier: string;
  status: 'ACTIVE' | 'CANCELLED' | 'EXPIRED';
  registeredAt: string;
  cancelledAt: string | null;
  usageLast30Days: BuyerDimensionUsage[];
  periodStart: string;
  periodEnd: string;
}

// ─── Analytics Types ──────────────────────────────────────────────────────────

export interface CustomerGrowthPoint {
  yearMonth: string;
  newCustomers: number;
}

export interface DimensionUsagePoint {
  yearMonth: string;
  dimension: string;
  totalQuantity: number;
}

export interface SubmissionHealthPoint {
  yearMonth: string;
  success: number;
  failed: number;
}

export interface IsvAnalytics {
  productCode: string;
  customerGrowth: CustomerGrowthPoint[];
  usageByDimension: DimensionUsagePoint[];
  submissionHealth: SubmissionHealthPoint[];
}

// ─── Response Wrappers ────────────────────────────────────────────────────────

export interface IsvDashboard {
  product: IsvProduct;
  eventStats: { pendingEvents: number; failedEvents: number };
  recentMeteringRecords: MeteringRecord[];
}
