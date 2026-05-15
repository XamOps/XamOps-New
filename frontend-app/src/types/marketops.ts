export interface MarketplaceSubscription {
  id: number;
  productCode: string;
  productTitle: string;
  vendorName: string | null;
  subscriptionStatus: 'ACTIVE' | 'INACTIVE' | 'EXPIRED';
  expiryDate: string | null;
  totalCostUsd: number;
  /** Present when AWS CE cannot attribute costs to individual products (multiple subscriptions). */
  accountTotalCostUsd?: number;
  syncedAt: string;

  // Agreement detail fields — populated from DescribeAgreement + GetAgreementTerms
  agreementId: string | null;
  startDate: string | null;
  /** PUBLIC | PRIVATE | ENTERPRISE_CONTRACT */
  offerType: string | null;
  /** FIXED_UPFRONT | RECURRING | PAYMENT_SCHEDULE | FREE_TRIAL | PAY_AS_YOU_GO | BYOL | CONFIGURABLE_UPFRONT */
  paymentSchedule: string | null;
  autoRenew: boolean | null;
  /** Human-readable e.g. "USD 500.00/month" or "USD 5000.00 upfront for 12 months" */
  paymentTermSummary: string | null;

  // PO fields — stored in XamOps only
  poNumber: string | null;
  poNotes: string | null;
}

// ── Phase 2a: Catalog Browse ────────────────────────────────────────────────

export interface CatalogProduct {
  listingId: string;
  name: string;
  shortDescription: string | null;
  sellerName: string | null;
  categories: string[];
  pricingModels: string[];
  fulfillmentTypes: string[];
  averageRating: number | null;
  badges: string[];
}

export interface CatalogBrowseResponse {
  products: CatalogProduct[];
  nextToken: string | null;
}

export interface CatalogFacetValue {
  value: string;
  count: number;
}

export interface CatalogFacetsResponse {
  facets: {
    CATEGORY?: CatalogFacetValue[];
    PRICING_MODEL?: CatalogFacetValue[];
    FULFILLMENT_OPTION_TYPE?: CatalogFacetValue[];
    PUBLISHER?: CatalogFacetValue[];
    [key: string]: CatalogFacetValue[] | undefined;
  };
}

// ── Product Detail ──────────────────────────────────────────────────────────

export interface PurchaseOption {
  purchaseOptionId?: string;
  purchaseOptionName?: string;
  purchaseOptionType?: string;
  sellerName?: string;
  availableFrom?: string;
  expiresAt?: string;
  badges?: string[];
  [key: string]: unknown;
}

export interface FulfillmentOption {
  fulfillmentOptionId?: string;
  type?: string;
  displayName?: string;
  name?: string;
  version?: string;
  usageInstructions?: string;
  fulfillmentUrl?: string;
  recommendedInstanceType?: string;
  operatingSystems?: { operatingSystemName?: string; operatingSystemFamilyName?: string }[];
  [key: string]: unknown;
}

export interface OfferTerm {
  termType: string;
  id?: string;
  currencyCode?: string;
  price?: string;
  duration?: string;
  billingPeriod?: string;
  refundPolicy?: string;
  maxTotalChargeAmount?: string;
  agreementDuration?: string;
  agreementEndDate?: string;
  agreementStartDate?: string;
  documents?: { name: string; url: string }[];
  rateCardCount?: number;
  grants?: unknown[];
  [key: string]: unknown;
}

export interface ProductDetailResponse {
  listing: Record<string, unknown> | null;
  purchaseOptions: PurchaseOption[];
  fulfillmentOptions: FulfillmentOption[];
  offerTerms: OfferTerm[];
}

// ── Phase 2d: Cancel result ─────────────────────────────────────────────────

export interface CancelSubscriptionResult {
  status: string;
  changeSetId: string;
  agreementId: string;
  subscriptionId: number;
  message: string;
}

// ── Phase 2e: PO update result ───────────────────────────────────────────────

export interface PoUpdateResult {
  status: string;
  subscriptionId: number;
  poNumber: string | null;
  poNotes: string | null;
}

/** Wrapper returned by the monthly billing endpoint */
export interface MeterBillingResponse {
  entries: MeterBillingMonth[];
  /** true when AWS CE billing could not be attributed to this specific product */
  accountLevel: boolean;
}

export interface MeterBillingMonth {
  yearMonth: string;
  usageType: string;
  usageQuantity: number;
  costUsd: number;
}

export interface DailyMeterBilling {
  date: string;
  costUsd: number;
  usageQuantity: number;
}

/** Wrapper returned by the daily billing endpoint */
export interface DailyMeterBillingResponse {
  entries: DailyMeterBilling[];
  accountLevel: boolean;
}

export interface MarketOpsSyncResult {
  status: string;
  cloudAccountId: string;
  subscriptionsFound: number;
  billingMonthsSynced: number;
  syncedAt: string;
}
