/**
 * GCP Performance Insights Types
 */

export type PerformanceSeverity = 'CRITICAL' | 'WARNING' | 'WEAK_WARNING';
export type PerformanceCategory = 'PERFORMANCE' | 'BEST_PRACTICE';

export interface PerformanceInsight {
  id: string;
  insight: string;
  description?: string;
  severity: PerformanceSeverity;
  category: PerformanceCategory;
  account: string;
  quantity?: number;
  resourceType: string;
  resourceId?: string;
  recommendation?: string;
  documentationUrl?: string;
  potentialSavings?: number;
  region?: string;
  createdDate?: string;
}

export interface PerformanceSummary {
  performanceScore: number;
  totalInsights: number;
  critical: number;
  warning: number;
  weakWarning: number;
}

export interface GcpPerformanceData {
  insights: PerformanceInsight[];
  summary: PerformanceSummary;
}
