export type AzureSecurityStatus = 'OK' | 'NO_DATA' | 'PERMISSION_ERROR' | 'API_ERROR';

export interface AzureSecurityAlert {
  alertId: string;
  alertName: string;
  severity: string;       // "High" | "Medium" | "Low" | "Informational"
  status: string;         // "Active" | "Dismissed" | "Resolved"
  resourceName: string;
  description: string;
  timeGenerated: string;
}

export interface AzureSecurityAssessment {
  assessmentId: string;
  displayName: string;
  severity: string;       // "High" | "Medium" | "Low"
  statusCode: string;     // "Unhealthy" | "Healthy" | "NotApplicable"
  resourceId: string;
  resourceName: string;
  category: string;
  remediation: string;
}

export interface AzureSecurityData {
  status: AzureSecurityStatus;
  secureScore: number;    // 0-100
  maxScore: number;       // typically 100
  alerts: AzureSecurityAlert[];
  assessments: AzureSecurityAssessment[]; // Unhealthy only
}
