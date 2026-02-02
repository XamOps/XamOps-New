/**
 * GCP Cost Management Types
 */

export interface GcpCostDataItem {
  name: string;
  amount: number;
}

export interface GcpForecastDataPoint {
  ds: string;
  yhat: number;
  yhat_lower: number;
  yhat_upper: number;
}

export type GcpGroupByType = 'SERVICE' | 'PROJECT' | 'TAG';

export interface GcpCostFilters {
  groupBy: GcpGroupByType;
  tagKey: string;
}

export interface GcpForecastState {
  isLoading: boolean;
  service: string;
  periods: number;
  availableServices: string[];
  data: GcpForecastDataPoint[];
}
