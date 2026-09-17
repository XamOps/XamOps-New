/**
 * GCP Alerts Type Definitions
 *
 * Matches backend DTOs from:
 * xamops-service/src/main/java/com/xammer/cloud/dto/AlertDto.java
 * xamops-service/src/main/java/com/xammer/cloud/dto/gcp/GcpAlertsResponse.java
 */

/**
 * Alert type classification
 */
export type GcpAlertType = 'QUOTA' | 'ANOMALY' | 'SECURITY';

/**
 * Alert status/severity levels
 */
export type GcpAlertStatus = 'CRITICAL' | 'HIGH' | 'WARNING' | 'OK' | 'UNKNOWN';

/**
 * Main GCP Alert interface (matches backend AlertDto)
 */
export interface GcpAlert {
  id: string;
  service: string;
  name: string;
  description: string;
  status: GcpAlertStatus;
  usage: number;
  limit: number;
  type: GcpAlertType;
  region: string;

  /**
   * How many underlying findings this row stands for.
   *
   * Security rows are grouped by category and resource, so one row can represent hundreds of
   * findings. 0 or absent means the row is not grouped and should be read as 1 — use
   * {@link alertCount} rather than testing for that here.
   */
  affectedCount?: number;

  /** The resources a grouped row covers, so it can be expanded without another request. */
  resources?: string[];
}

/** The findings behind a row, treating an ungrouped alert as one. */
export const alertCount = (alert: GcpAlert): number => Math.max(alert.affectedCount ?? 0, 1);

/**
 * The alerts endpoint response envelope.
 *
 * The endpoint used to return a bare array, which could not express the difference between "checked,
 * nothing found" and "could not check" — both arrived as `[]` and the page rendered a green
 * "All Clear!" for each.
 */
export interface GcpAlertsResponse {
  alerts: GcpAlert[];
  /** ISO timestamp of when the data was built, not when it was served. */
  generatedAt: string | null;
  /** True when at least one source could not be checked, so the list may be incomplete. */
  degraded: boolean;
  degradedReasons: string[];
  /** True when served from a cache rather than rebuilt on this request. */
  fromCache: boolean;
  /** Underlying findings represented, which exceeds `alerts.length` when rows are grouped. */
  totalFindings: number;
}

/**
 * Counts for the KPI strip.
 *
 * Severity-based, not usage-percentage based. The previous shape counted "critical / warning /
 * healthy" with the subtitles "above 90%", "75–90%" and "below 75%", which are quota semantics.
 * Applied to a project whose alerts were 99.9% security findings — all of them carrying
 * `usage: 0, limit: 0` — the strip claimed thousands of things were "above 90%" of nothing, and the
 * "healthy" tile was structurally always zero because the backend only emits CRITICAL and HIGH
 * security rows.
 */
export interface GcpAlertCounts {
  /** Rows after grouping — what the table shows. */
  rows: number;
  /** Underlying findings — what the cloud provider reported. */
  findings: number;
  critical: number;
  high: number;
  costAnomalies: number;
  quotaWarnings: number;
}

/**
 * Severity class for CSS styling
 */
export type GcpAlertSeverityClass = 'critical' | 'warning' | 'normal';

/**
 * Service icon mapping configuration
 */
export interface ServiceIconConfig {
  icon: string;
  colorClass: string;
}
