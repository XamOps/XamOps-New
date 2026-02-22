export type AzureAlertsStatus = 'OK' | 'NO_DATA' | 'PERMISSION_ERROR' | 'API_ERROR';

export interface AzureMonitorAlert {
  alertId: string;
  alertName: string;
  description: string;
  severity: string;      // "Sev0" | "Sev1" | "Sev2" | "Sev3" | "Sev4"
  severityLabel: string; // "Critical" | "Error" | "Warning" | "Informational" | "Verbose"
  alertState: string;    // "New" | "Acknowledged" | "Closed"
  monitorCondition: string; // "Fired" | "Resolved"
  signalType: string;    // "Metric" | "Log" | "Activity Log" | "Health"
  resourceName: string;
  resourceGroup: string;
  resourceType: string;
  startDateTime: string;
}

export interface AzureAlertsData {
  status: AzureAlertsStatus;
  alerts: AzureMonitorAlert[];
}
