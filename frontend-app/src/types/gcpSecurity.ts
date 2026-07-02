/**
 * GCP Security Types
 *
 * Type definitions for GCP Security Center data
 */

export type GcpSeverityLevel = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';

export interface GcpSecurityFinding {
  resourceName: string;
  category: string;
  description: string;
  severity: GcpSeverityLevel;
  compliance?: string;
  complianceControl?: string; // SOC2 control, e.g. "SOC2 CC6.1"
}

export interface GcpIamDriftDetail {
  member: string;
  role: string;
  status: string; // "PUBLIC_ACCESS" | "PRIMITIVE_ROLE" | "SA_PRIMITIVE_ROLE"
}

export interface GcpIamDrift {
  resourceName: string;
  driftDetails: GcpIamDriftDetail[];
}

export interface GcpContainerVulnerability {
  imageName: string;
  severity: string;       // "CRITICAL" | "HIGH" | "MEDIUM" | "LOW"
  packageName: string;
  version: string;
  cveId: string;
  description: string;
}

export interface GcpSecurityData {
  findings: GcpSecurityFinding[];
  iamDrift: GcpIamDrift[];
  vulnerabilities: GcpContainerVulnerability[];
}

export interface GcpSeverityCounts {
  CRITICAL: number;
  HIGH: number;
  MEDIUM: number;
  LOW: number;
}
