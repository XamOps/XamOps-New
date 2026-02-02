/**
 * GCP Alerts Type Definitions
 *
 * Matches backend AlertDto structure from:
 * xamops-service/src/main/java/com/xammer/cloud/dto/AlertDto.java
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
}

/**
 * Alert counts for KPI cards
 */
export interface GcpAlertCounts {
  total: number;
  critical: number;
  warning: number;
  healthy: number;
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
