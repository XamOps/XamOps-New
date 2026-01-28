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
  recommendedInstances: number;
  onDemandCost: string;
  estimatedMonthlyCost: string;
  monthlySavings: string;
}

export interface ReservationData {
  analysis: ReservationAnalysis;
  historicalData: HistoricalData;
  inventory: ReservationInventoryItem[];
  savingsPlans: SavingsPlan[];
  purchaseRecommendations: PurchaseRecommendation[];
}

export interface RecommendationFilters {
  term: 'ONE_YEAR' | 'THREE_YEARS';
  paymentOption: 'NO_UPFRONT' | 'PARTIAL_UPFRONT' | 'ALL_UPFRONT';
  lookback: 'SEVEN_DAYS' | 'THIRTY_DAYS' | 'SIXTY_DAYS';
  offeringClass: 'STANDARD' | 'CONVERTIBLE';
}

export type TabType = 'recommendations' | 'overview';
export type InventoryTabType = 'ris' | 'sps';
