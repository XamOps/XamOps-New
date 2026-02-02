/**
 * TypeScript types for GCP Reservations (Committed Use Discounts)
 *
 * Converted from: gcp_reservations.html
 */

export interface GcpActiveCud {
  name: string;
  description?: string;
  status: 'ACTIVE' | 'INACTIVE' | 'EXPIRED';
  commitmentUnit?: string;
  type?: string;
  term?: string;
  plan?: string;
  region?: string;
  commitmentAmount?: number;
}

export interface GcpCudRecommendation {
  recommendationId?: string;
  currentMachineType?: string;
  location?: string;
  monthlySavings?: number;
  reason?: string;
  description?: string;
}

export interface GcpReservationsData {
  activeCuds: GcpActiveCud[];
  cudRecommendations: GcpCudRecommendation[];
}
