/**
 * Security Types
 *
 * TypeScript interfaces for security findings, prowler status, and related data
 */

export interface SecurityFinding {
  resourceId: string;
  region: string;
  category: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | string;
  description: string;
  complianceFramework?: string;
  controlId?: string;
}

export interface ProwlerStatus {
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'UNKNOWN';
  message?: string;
  error?: string;
  findingsCount?: number;
  startTime?: string;
  endTime?: string;
}

export interface SeverityCounts {
  Critical: number;
  High: number;
  Medium: number;
  Low: number;
}

export type SeverityLevel = 'Critical' | 'High' | 'Medium' | 'Low';

export interface SecurityState {
  isLoading: boolean;
  isRefreshing: boolean;
  isExporting: boolean;
  findings: SecurityFinding[];
  openSeverities: SeverityLevel[];
  selectedCompliance: string;
  prowlerStatus: ProwlerStatus;
}
