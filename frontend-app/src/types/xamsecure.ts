export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | string;

export interface PageResp<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface ThreatDetection {
  id: number;
  upwindId: string;
  category: string | null;
  severity: Severity | null;
  status: string | null;
  title: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  resourceName: string | null;
  resourceType: string | null;
  mitreTactics: string | null;
  occurrenceCount: number | null;
  firstSeenTime: string | null;
  lastSeenTime: string | null;
  rawPayload: string | null;
}

export interface VulnerabilityFinding {
  id: number;
  upwindId: string;
  cveId: string | null;
  vulnerabilityName: string | null;
  severity: Severity | null;
  cvssScore: string | null;
  epssScore: string | null;
  exploitable: boolean | null;
  status: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  region: string | null;
  resourceName: string | null;
  resourceType: string | null;
  firstSeenTime: string | null;
  lastScanTime: string | null;
  rawPayload: string | null;
}

export interface PostureFinding {
  id: number;
  upwindId: string;
  severity: Severity | null;
  status: string | null;
  title: string | null;
  frameworkTitle: string | null;
  checkTitle: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  region: string | null;
  resourceName: string | null;
  resourceType: string | null;
  firstSeenTime: string | null;
  lastSeenTime: string | null;
  rawPayload: string | null;
}

export interface ComplianceFramework {
  framework: string;
  failCount: number;
  passCount: number;
}

export interface SyncStateEntry {
  domain: string;
  lastSuccessAt: string | null;
  lastError: string | null;
}

export interface XamSecureSummary {
  threatsBySeverity: Record<string, number>;
  vulnerabilitiesBySeverity: Record<string, number>;
  postureBySeverity: Record<string, number>;
  syncState: SyncStateEntry[];
}

export interface OnboardingAccount {
  accountName: string;
  cloudAccountId: string;
  provider: string;
  status: 'NOT_CONNECTED' | 'PENDING' | 'CONNECTED';
  sensorInstallCommand: string | null;
}

export const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

export function severityTotal(m: Record<string, number> | undefined): number {
  if (!m) return 0;
  return Object.values(m).reduce((a, b) => a + b, 0);
}
