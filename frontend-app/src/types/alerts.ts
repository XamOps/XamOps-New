/**
 * Alert Types
 *
 * TypeScript types for the Alerts page
 * Converted from: alerts.html (Alpine.js component)
 */

export type AlertType = 'QUOTA' | 'ANOMALY';

export interface QuotaAlert {
  id: string;
  service: string;
  type: 'QUOTA';
  name: string;
  limit: number;
  usage: number;
  description: string;
  region: string;
}

export interface AnomalyAlert {
  id: string;
  service: string;
  type: 'ANOMALY';
  name: string;
  usage: number;
  description: string;
  region: string;
}

export type Alert = QuotaAlert | AnomalyAlert;

export interface GrafanaAlert {
  id: string;
  name: string;
  status: 'firing' | 'resolved';
  description?: string;
  region?: string;
}

export interface AlertCounts {
  total: number;
  critical: number;
  warning: number;
  healthy: number;
}

export type AlertTab = 'cloudguard' | 'grafana';

export type AlertSeverityClass = 'critical' | 'warning' | 'normal';

/**
 * Enhanced Alert Response Types (for error handling)
 */

export type AlertErrorType =
  | 'TIMEOUT'
  | 'API_ERROR'
  | 'PERMISSION_DENIED'
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
