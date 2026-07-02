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
  complianceControl?: string; // SOC2 control, e.g. "SOC2 CC6.1"
}

export interface AzureSecureScoreControl {
  controlName: string;
  currentScore: number;
  maxScore: number;
  percentage: number;            // 0-100
  healthyResourceCount: number;
  unhealthyResourceCount: number;
  recoverableScore: number;      // maxScore - currentScore
}

export interface AzureSecurityData {
  status: AzureSecurityStatus;
  secureScore: number;    // 0-100
  maxScore: number;       // typically 100
  alerts: AzureSecurityAlert[];
  assessments: AzureSecurityAssessment[]; // Unhealthy only
  scoreControls: AzureSecureScoreControl[]; // per-control breakdown
}
