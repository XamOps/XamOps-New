/**
 * GCP Rightsizing Types
 */

export type RecommendationType = 'COST_SAVINGS' | 'PERFORMANCE_IMPROVEMENT';

export interface GcpRightsizingRecommendation {
  recommendationId?: string;
  resourceName: string;
  service: string;
  location?: string;
  currentMachineType?: string;
  recommendedMachineType?: string;
  recommendationType: RecommendationType;
  reason?: string;
  description?: string;
  monthlySavings: number;
}

export type ServiceFilter = 'All' | 'Compute Engine' | 'Cloud SQL' | 'Commitment';
