/**
 * TypeScript types for Reservation Management
 *
 * Converted from: reservation.html
 */

export interface ReservationInventoryItem {
  reservationId: string;
  instanceType: string;
  instanceCount: number;
  scope: string;
  availabilityZone?: string;
  offeringType: string;
  state: 'active' | 'retired' | 'expired' | 'pending';
  utilizationPercentage: number;
  start: string;
  end: string;
}

export interface SavingsPlan {
  savingsPlanId: string;
  type: string;
  region?: string;
  commitment: string;
  paymentOption: string;
  state: string;
  start: string;
  end: string;
  utilizationPercentage?: number;
  usedCommitment?: string;
  unusedCommitment?: string;
  netSavings?: string;
}

export interface SavingsPlanRecommendation {
  savingsPlanType: string;
  region?: string;
  instanceFamily?: string;
  recommendedCommitment: string;
  upfrontCost?: string;
  estimatedMonthlyCost: string;
  estimatedMonthlySavings: string;
  estimatedSavingsPercentage: string;
  currentAverageHourlyOnDemandSpend: string;
}

export interface SpRecommendationFilters {
  spType: 'COMPUTE_SP' | 'EC2_INSTANCE_SP' | 'SAGEMAKER_SP';
  term: 'ONE_YEAR' | 'THREE_YEARS';
  paymentOption: 'NO_UPFRONT' | 'PARTIAL_UPFRONT' | 'ALL_UPFRONT';
  lookback: 'SEVEN_DAYS' | 'THIRTY_DAYS' | 'SIXTY_DAYS';
}

export interface HistoricalDataPoint {
  date: string;
  utilizationPercentage: number;
  coveragePercentage: number;
}

export interface HistoricalData {
  labels: string[];
  utilizationPercentages: number[];
  coveragePercentages: number[];
}

export interface ReservationAnalysis {
  utilizationPercentage: number;
  coveragePercentage: number;
}

export interface PurchaseRecommendation {
  instanceFamily: string;
  region: string;
  platform: string;
  /** Serialised as a string by the backend, not a number. */
  recommendedInstances: string | number;
  onDemandCost: string;
  estimatedMonthlyCost: string;
  monthlySavings: string;
  /**
   * The term Cost Explorer actually returned for this recommendation, e.g. `ONE_YEAR`.
   * Was previously absent from this type, so the table fell back to rendering the filter
   * state and could not show a row whose term differed from the current selection.
   */
  term?: string;
  instanceType?: string;
  tenancy?: string;
  recommendedUnits?: string;
  minimumUnits?: string;
  /** `Current` or `Previous` generation. */
  generation?: string;
  sizeFlex?: boolean;
}

export interface ReservationData {
  analysis: ReservationAnalysis;
  historicalData: HistoricalData;
  inventory: ReservationInventoryItem[];
  savingsPlans: SavingsPlan[];
  purchaseRecommendations: PurchaseRecommendation[];
  savingsPlanRecommendations: SavingsPlanRecommendation[];
}

export interface RecommendationFilters {
  term: 'ONE_YEAR' | 'THREE_YEARS';
  paymentOption: 'NO_UPFRONT' | 'PARTIAL_UPFRONT' | 'ALL_UPFRONT';
  lookback: 'SEVEN_DAYS' | 'THIRTY_DAYS' | 'SIXTY_DAYS';
  offeringClass: 'STANDARD' | 'CONVERTIBLE';
}

export type TabType = 'recommendations' | 'overview';
export type InventoryTabType = 'ris' | 'sps';
