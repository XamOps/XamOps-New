/**
 * Cost Management Types
 */

export interface CostDataItem {
  name: string;
  amount: number;
}

export interface HistoricalData {
  labels: string[];
  costs: number[];
}

export interface ForecastData {
  dates: string[];
  predictions: number[];
  upperBounds: number[];
  lowerBounds: number[];
}

export interface ForecastResponse {
  status: string;
  forecast: ForecastData | null;
}

export type GroupByType = 'SERVICE' | 'REGION' | 'TAG';

export interface CostFilters {
  startDate: string;
  endDate: string;
  groupBy: GroupByType;
  tagKey: string;
}

export interface HistoricalState {
  isLoading: boolean;
  dimension: string | null;
  data: HistoricalData | null;
}

export interface ForecastState {
  isLoading: boolean;
  data: ForecastData | null;
  service: string;
  periods: number;
  availableServices: string[];
}
