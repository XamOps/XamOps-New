/**
 * WAR — Workload Attack-surface Review
 *
 * Mirrors com.xammer.cloud.dto.war.* in xamops-service. Keep in sync with
 * WarReport.java / WarFinding.java.
 */

export type WarStatus =
  | 'PASS'
  | 'FAIL'
  | 'WARN'
  | 'MANUAL'
  | 'UNKNOWN'
  | 'NOT_APPLICABLE';

export type WarSeverity = 'Critical' | 'High' | 'Medium' | 'Low';

/** UNKNOWN means the scan could not verify anything, not that risk is unmeasured-but-low. */
export type WarRiskLevel = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'UNKNOWN';

/** State of an attack path across all the controls that feed it. */
export type WarPathState = 'OPEN' | 'PARTIAL' | 'CONTAINED' | 'UNVERIFIED';

export interface WarFinding {
  // Control identity, from the security advisory
  controlId: string;
  ordinal: number;
  category: string;
  subCategory: string;
  title: string;
  description: string;
  severity: string;
  tools: string;
  reference: string;
  controlType: string;
  businessImpact: string;
  impactLevel: string;
  attackPaths: string[];
  attackPathLabels: string[];

  // Observed state, from this account
  status: WarStatus;
  automated: boolean;
  summary: string | null;
  evidence: string[];
  affectedCount: number;
  scannedCount: number;
  region: string | null;
  /** IAM action to grant when status is UNKNOWN. */
  deniedAction: string | null;
  riskWeight: number;
}

export interface WarAttackPathSummary {
  path: string;
  label: string;
  description: string;
  failed: number;
  warned: number;
  /** Controls on this path that could not be verified. */
  unverified: number;
  total: number;
  affectedResources: number;
  state: WarPathState;
  worstSeverity: string;
  topControlIds: string[];
}

export interface WarCategorySummary {
  category: string;
  total: number;
  passed: number;
  failed: number;
  warned: number;
  manual: number;
  unknown: number;
  notApplicable: number;
  score: number;
}

export interface WarReport {
  accountId: string;
  accountName: string | null;
  provider: string;
  generatedAt: string;
  scannedRegions: string[];

  warScore: number;
  riskLevel: WarRiskLevel;

  totalControls: number;
  automatedControls: number;
  /** Controls that produced a real verdict (PASS, FAIL or WARN). */
  verifiedControls: number;
  /** False when nothing could be verified — score and riskLevel are then meaningless. */
  assessable: boolean;
  /** Single dominant cause of check failures, e.g. expired credentials. */
  blockedReason: string | null;

  statusCounts: Record<string, number>;
  severityCounts: Record<string, number>;

  attackPaths: WarAttackPathSummary[];
  categories: WarCategorySummary[];
  findings: WarFinding[];
  topRisks: WarFinding[];
  /** Actions explicitly denied to the role — a real IAM gap worth granting. */
  deniedActions: string[];
  /** Actions that failed for a non-authorization reason. Granting these fixes nothing. */
  blockedActions: string[];
  scanWarnings: string[];
}

/** Statuses that represent live, usable attack surface. */
export const OPEN_STATUSES: WarStatus[] = ['FAIL', 'WARN', 'UNKNOWN'];

export const SEVERITY_ORDER: WarSeverity[] = ['Critical', 'High', 'Medium', 'Low'];

export const isOpen = (status: WarStatus): boolean =>
  OPEN_STATUSES.includes(status);
