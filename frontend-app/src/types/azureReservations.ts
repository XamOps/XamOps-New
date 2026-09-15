export interface AzureReservationItem {
  reservationId: string;
  displayName: string;
  sku: string;
  quantity: number;
  state: string;
  term: string;
  billingPlan: string;
  expiryDate: string;
  location: string;
  scope: string;
  /**
   * Percentage consumed, or null/absent when Azure did not report it.
   *
   * <p>Nullable deliberately. The backend previously sent a hardcoded 0, which would render as a
   * completely unused reservation — an urgent, expensive finding — when the truth is only that
   * the reservations list endpoint carries no consumption data. Treat null as unknown and render
   * it as such rather than defaulting to 0.
   */
  utilizationPercentage?: number | null;
  /** Why utilizationPercentage is absent. */
  utilizationUnavailableReason?: string | null;
}

export interface AzureSavingsPlanItem {
  savingsPlanId: string;
  displayName: string;
  commitment: string;
  paymentOption: string;
  term: string;
  state: string;
  startDate: string;
  expiryDate: string;
  appliedScope: string;
}

export interface AzureReservationMetrics {
  totalReservations: number;
  activeReservations: number;
  totalSavingsPlans: number;
  activeSavingsPlans: number;
}

/**
 * A reservation purchase recommendation from Azure's Consumption API.
 *
 * <p>Every figure is per-SKU and computed by Azure from this subscription's own metered usage at
 * its own rates, normalised to a 30-day month. It replaces a flat 20%-of-spend estimate that the
 * backend applied to entire billing service lines, where both the rate and the base were invented.
 */
export interface AzureReservationRecommendation {
  /** Azure's identifier for the recommendation. */
  recommendationId?: string | null;
  /** Readable service label derived from the Azure resource type. */
  serviceName: string;
  /** Raw Azure resource type, e.g. "virtualmachines". */
  resourceType?: string | null;
  /** The specific SKU to reserve, e.g. "Standard_D2ds_v4". */
  sku?: string | null;
  region?: string | null;
  /** "Single" (this subscription) or "Shared" (billing scope). */
  scope?: string | null;
  recommendedQuantity?: number | null;
  /** Projected 30-day cost on pay-as-you-go rates. */
  currentMonthlyCost: number;
  /** Projected 30-day cost with the reservation in place. */
  reservedMonthlyCost?: number;
  estimatedSavingsPercentage: number;
  estimatedMonthlySavings: number;
  /** Term as Azure reports it, e.g. "P1Y" / "P3Y". */
  recommendedTerm: string;
  /** The other term Azure costed for the same SKU. Alternatives, not additive. */
  alternativeTerm?: string | null;
  alternativeTermMonthlySavings?: number | null;
  /**
   * ISO currency code, or null on Azure's legacy response shape which states none.
   *
   * <p>Do not assume a symbol when this is absent. The figures are in the enrollment's billing
   * currency, which we do not know from that shape, so rendering a ₹ or $ would be a guess with a
   * large error factor.
   */
  currency?: string | null;
  /** Days of usage Azure analysed: 7, 30 or 60. */
  lookbackDays?: number;
  firstUsageDate?: string | null;
  reason: string;
  /**
   * True when Azure returned the recommendation but not a readable savings amount.
   * Never sum these into a headline savings figure.
   */
  indicativeOnly?: boolean;
}

export interface AzureReservationData {
  reservations: AzureReservationItem[];
  savingsPlans: AzureSavingsPlanItem[];
  metrics: AzureReservationMetrics;
  recommendations: AzureReservationRecommendation[];
  /** Priced monthly savings. Excludes anything flagged indicativeOnly. */
  totalMonthlySavings?: number;
  /** Unpriced savings, kept apart from totalMonthlySavings. */
  indicativeMonthlySavings?: number;
  indicativeRecommendationCount?: number;
  /**
   * Why recommendations is empty, or null when Azure answered normally.
   *
   * <p>An empty list means either "Azure found nothing worth reserving" or "we could never ask",
   * and only this field distinguishes them. The second case is normally a missing role assignment
   * or an unsupported subscription offer, both of which the customer can act on.
   */
  recommendationsUnavailableReason?: string | null;
  /** ISO currency of the recommendation figures, when Azure stated exactly one. */
  recommendationCurrency?: string | null;
  /** True when Azure reported more than one currency, so no total is published. */
  mixedCurrencies?: boolean;
  /**
   * Why totalMonthlySavings is not a comparable figure, or null when it is.
   *
   * Covers mixed currencies and a non-USD enrollment reporting into a USD cross-provider total. No
   * exchange rate is applied anywhere, so this is flagged rather than silently converted.
   */
  savingsUnavailableReason?: string | null;
}

export type AzureReservationTabType = 'overview' | 'recommendations';
export type AzureInventoryTabType = 'ris' | 'sps';
