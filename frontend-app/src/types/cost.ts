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

/**
 * Forecast accuracy metrics — measures how close past ML predictions were
 * to the realised actual cost. Populated by the backend accuracy tracker.
 */
export interface ForecastAccuracyMetrics {
  provider: string;
  accountId: string;
  serviceName: string | null;
  evaluatedCount: number;
  pendingCount: number;
  skippedCount: number;
  mape: number | null;            // Mean Absolute Percentage Error
  mae: number | null;             // Mean Absolute Error (currency)
  accuracyScore: number | null;   // 0-100 (100 - MAPE)
  confidenceCoverage: number | null; // % of actuals inside predicted band
  dataQuality: 'GOOD' | 'FAIR' | 'POOR' | 'INSUFFICIENT_DATA';
  message: string;
  mapeByHorizon?: Record<string, number>;
}

export type GroupByType = 'SERVICE' | 'REGION' | 'TAG';

export interface CostFilters {
  startDate: string;
  endDate: string;
  groupBy: GroupByType;
  tagKey: string;
  labelId?: number | null;
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
