export type RightsizingStatus = 'OK' | 'NO_DATA' | 'PERMISSION_ERROR' | 'API_ERROR';

export interface AzureRightsizingResponse {
  status: RightsizingStatus;
  recommendations: AzureRightsizingRecommendation[];
}

export interface AzureRightsizingRecommendation {
  recommendationId: string;
  resourceId: string;
  resourceName: string;
  resourceType: string;
  category: string;
  impact: string;       // 'High' | 'Medium' | 'Low'
  currentSku: string;
  targetSku: string;
  monthlySavings: number;
  annualSavings: number;
  currency: string;
  problem: string;
  solution: string;
  /**
   * Highest observed CPU utilization. Renamed from `maxCpuP95` because the backend
   * stores the true Azure Monitor Maximum for metric-based recommendations, not a
   * 95th percentile.
   */
  maxCpuObserved: string;
  /** Average observed CPU utilization. */
  avgCpuObserved?: string;
  /** Highest observed memory utilization; blank unless an in-VM agent reports it. */
  maxMemoryObserved: string;
  /** True when savings could not be quantified; exclude from savings totals. */
  savingsUnknown?: boolean;
  location: string;
}
