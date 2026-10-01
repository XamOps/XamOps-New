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

/**
 * How the breakdown is grouped.
 *
 * <p>`REGION` was missing here while the backend supported it all along — `GcpCostService`
 * handles it and `/costs/summary` already asks for it. The dropdown offered Service, Project and
 * Tag, so a reader could not ask the one question the AWS and Azure pages both answer by default.
 */
export type GcpGroupByType = 'SERVICE' | 'REGION' | 'PROJECT' | 'TAG';

export interface GcpCostFilters {
  groupBy: GcpGroupByType;
  tagKey: string;
  /**
   * Inclusive range, `YYYY-MM-DD`.
   *
   * <p>`/costs/breakdown` has accepted `startDate` and `endDate` since it was written and the page
   * never sent them, so every GCP breakdown was whatever default range the service picked. "Last
   * month" and "the first twelve days" were unaskable questions on a page whose AWS and Azure
   * equivalents both answer them.
   */
  startDate: string;
  endDate: string;
}

export interface GcpForecastState {
  isLoading: boolean;
  service: string;
  periods: number;
  availableServices: string[];
  data: GcpForecastDataPoint[];
}

/**
 * Why a GCP project's cost page looks the way it does.
 *
 * Mirrors com.xammer.cloud.dto.gcp.GcpBillingStatusDto. GCP has no API that returns
 * actual spend — cost data only exists if the customer enabled Cloud Billing export to
 * BigQuery, which cannot be turned on programmatically. So "no data" has several
 * causes with different fixes, and the page used to render all of them, plus genuine
 * zero spend, as the same blank panel.
 */
export type GcpBillingState =
  /** The source is usable. An empty cost list alongside this means zero spend. */
  | 'READY'
  /** No billing export exists for the project — needs a manual console step. */
  | 'EXPORT_NOT_CONFIGURED'
  /** A table is recorded but malformed or missing — needs correcting, not creating. */
  | 'EXPORT_TABLE_INVALID'
  /** Export exists but has no rows yet; normal for up to 24h after setup. */
  | 'EXPORT_PENDING'
  /** Reseller-billed but no CSP dataset is linked. A Xammer-side issue. */
  | 'CSP_DATASET_UNAVAILABLE'
  /** BigQuery refused the read — the service account needs roles. */
  | 'ACCESS_DENIED'
  /** No usable service account key, so BigQuery was never reachable. */
  | 'CREDENTIALS_MISSING'
  /** Something else failed. Reported rather than shown as zero spend. */
  | 'UNKNOWN';

/** Where a GCP project's costs are read from. Configuration, not readiness. */
export type GcpBillingSource = 'OWN_EXPORT' | 'XAMMER_CSP_DATASET' | 'XAMMER_EXPORT';

export interface GcpBillingStatus {
  projectId: string;
  state: GcpBillingState;
  billingSource: GcpBillingSource | null;
  /** Fully-qualified export table backing this project, when one resolved. */
  exportTable: string | null;
  /**
   * Ready-to-show sentence built by the backend, or null when READY. Null is
   * meaningful: it tells the page an empty chart is real zero spend, not a fault.
   */
  summary: string | null;
  /** Technical detail for support. Not intended for display. */
  detail: string | null;
  /** True when the customer can resolve this themselves. */
  actionRequired: boolean;
}
