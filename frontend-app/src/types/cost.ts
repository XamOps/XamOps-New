/**
 * Cost Management Types
 */

export interface CostDataItem {
  name: string;
  amount: number;
  /**
   * True when `amount` was reconstructed from a non-authoritative rate rather than read from
   * billing data.
   *
   * Cost Explorer reports per service, not per resource, so resource-level rows are partly rebuilt
   * as `rate × hours`. When that rate is a live AWS Price List lookup the figure is sound; when it
   * falls back to a 2024 table entry or a size-pattern guess it is a projection. Absent on rows that
   * are genuinely billed data, so `estimated === true` is the only positive signal.
   */
  estimated?: boolean;
  /** Why `amount` is an estimate — suitable for a tooltip. Absent when it is billed data. */
  estimateBasis?: string;
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
