export type AzurePerformanceStatus = 'OK' | 'NO_DATA' | 'PERMISSION_ERROR' | 'API_ERROR';

export interface AzurePerformanceInsight {
  insightId: string;
  resourceName: string;
  resourceType: string;
  region: string;
  severity: string; // "High" | "Medium" | "Low"
  description: string;
  recommendation: string;
}

export interface AzurePerformanceData {
  status: AzurePerformanceStatus;
  performanceScore: number;
  insights: AzurePerformanceInsight[];
}
