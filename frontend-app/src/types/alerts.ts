/**
 * Alert Types
 *
 * TypeScript types for the Alerts page
 * Converted from: alerts.html (Alpine.js component)
 */

export type AlertType = 'QUOTA' | 'ANOMALY' | 'SECURITY';

/** The backend's normalised severity (AlertDto.getSeverity), the same scale on every alert type. */
export type AlertSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFORMATIONAL';

interface AlertBase {
  id: string;
  service: string;
  name: string;
  description: string;
  region: string;
  severity: AlertSeverity;
}

export interface QuotaAlert extends AlertBase {
  type: 'QUOTA';
  limit: number;
  usage: number;
}

export interface AnomalyAlert extends AlertBase {
  type: 'ANOMALY';
  /** Spend above what Cost Explorer expected, in `currencySymbol`. */
  usage: number;
  currencySymbol: string;
}

export interface SecurityAlert extends AlertBase {
  type: 'SECURITY';
  /**
   * Where the finding came from. Security Hub findings open in the AWS console; live findings
   * (GuardDuty, Config, CloudTrail-derived) exist only in XamOps, so they open the Security Center.
   */
  source: 'SECURITY_HUB' | 'LIVE';
}

export type Alert = QuotaAlert | AnomalyAlert | SecurityAlert;

export interface AlertCounts {
  total: number;
  critical: number;
  /** HIGH and MEDIUM together: worth a look, not an emergency. */
  warning: number;
}

export type AlertTab = 'cloudguard' | 'k8s';

/** Where an alert's action button goes. Internal links stay in the app. */
export interface AlertLink {
  href: string;
  label: string;
  external: boolean;
}


/**
 * Enhanced Alert Response Types (for error handling)
 */

export type AlertErrorType =
  | 'TIMEOUT'
  | 'API_ERROR'
  | 'PERMISSION_DENIED'
  | 'CREDENTIALS_EXPIRED'
  | 'RATE_LIMIT'
  | 'SERVICE_NOT_ENABLED'
  | 'UNKNOWN';

export interface AlertError {
  service: string;
  errorType: AlertErrorType;
  message: string;
  partial: boolean;
  timestamp: string;
}

export interface AlertsMetadata {
  totalAlerts: number;
  vpcQuotaCount: number;
  networkQuotaCount: number;
  costAnomalyCount: number;
  securityAlertCount: number;
  fetchDurationMs: number;
  timestamp: string;
  fromCache: boolean;
}

export interface AlertsResponse {
  alerts: Alert[];
  errors: AlertError[];
  metadata: AlertsMetadata;
}
