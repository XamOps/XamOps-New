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
  utilizationPercentage: number;
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

export interface AzureReservationRecommendation {
  serviceName: string;
  currentMonthlyCost: number;
  estimatedSavingsPercentage: number;
  estimatedMonthlySavings: number;
  recommendedTerm: string;
  reason: string;
}

export interface AzureReservationData {
  reservations: AzureReservationItem[];
  savingsPlans: AzureSavingsPlanItem[];
  metrics: AzureReservationMetrics;
  recommendations: AzureReservationRecommendation[];
}

export type AzureReservationTabType = 'overview' | 'recommendations';
export type AzureInventoryTabType = 'ris' | 'sps';
