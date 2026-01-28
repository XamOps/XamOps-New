/**
 * Performance Insights Types
 *
 * TypeScript types for the Performance Insights page
 */

export type InsightSeverity = 'CRITICAL' | 'WARNING' | 'WEAK_WARNING';
export type InsightCategory = 'BEST_PRACTICE' | 'PERFORMANCE' | 'COST' | 'SECURITY';
export type SeverityFilter = 'ALL' | InsightSeverity;
export type ActiveTab = 'insights' | 'best_practices';

export interface PerformanceInsight {
  id: string;
  insight: string;
  description?: string;
  severity: InsightSeverity;
  category: InsightCategory;
  account: string;
  quantity: number;
  resourceType?: string;
  resourceId?: string;
  recommendation?: string;
  documentationUrl?: string;
  potentialSavings: number;
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

export interface ToastState {
  show: boolean;
  message: string;
  type: 'success' | 'error';
}
