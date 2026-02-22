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
  maxCpuP95: string;
  maxMemoryP95: string;
  location: string;
}
