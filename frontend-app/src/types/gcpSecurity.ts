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
}

export interface GcpIamDrift {
  // IAM drift data structure
  [key: string]: any;
}

export interface GcpContainerVulnerability {
  // Container vulnerability data structure
  [key: string]: any;
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
