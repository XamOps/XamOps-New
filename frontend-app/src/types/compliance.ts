/**
 * ComplianceOps types, mirroring com.xammer.cloud.dto.compliance.ComplianceReport.
 *
 * Everything here is aggregated server-side. The page used to receive the full raw
 * scanner output (tens of thousands of findings) and re-derive control identity in the
 * browser from a truncated description, so control totals moved whenever the scanner
 * reworded a message. Controls now arrive already keyed by check id.
 */

/** WAIVED is applied at read time when an approved, unexpired exception covers a check. */
export type ComplianceStatus = 'PASS' | 'FAIL' | 'MANUAL' | 'WAIVED';

export type ComplianceSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | string;

export interface ComplianceCounts {
  totalControls: number;
  passedControls: number;
  failedControls: number;
  manualControls: number;
  waivedControls: number;
  /**
   * passed + failed + waived — the score's denominator, sent by the server rather than
   * re-derived here. The header used to add up its own and drifted from the one the score
   * was calculated over, which is how the dial and the caption beside it disagreed.
   */
  settledControls: number;
  /** Controls whose severity the scanner did not classify. Weighted as medium on an assumption. */
  unclassifiedSeverity: number;
  passedResources: number;
  failedResources: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
  newlyFailing: number;
  newlyPassing: number;
}

export interface ComplianceFrameworkScore {
  /** Raw framework id as the scanner reported it, e.g. "CIS-3.0". */
  framework: string;
  totalControls: number;
  passedControls: number;
  failedControls: number;
  manualControls: number;
  waivedControls: number;
  /** passed + failed + waived, for this framework's mapped checks. */
  settledControls: number;
  /** Unweighted share of settled controls that pass. Same rules as the overall score. */
  score: number | null;
  /** Severity-weighted pass rate. A risk view of this framework, not a compliance one. */
  riskWeightedScore: number | null;
  /** Share of this framework's mapped checks that reached a settled outcome. */
  coverage: number | null;
  assessable: boolean;
  critical: number;
  high: number;
  medium: number;
  low: number;
  /** Distinct framework clauses this scan actually touched. */
  frameworkControlCount: number;
  /**
   * Total requirements the framework defines, from the backend baseline. Null when no baseline
   * is on record for this provider and version — in which case the UI must not imply one.
   */
  baselineControlCount: number | null;
  /** frameworkControlCount as a share of baselineControlCount. Null whenever the baseline is. */
  clauseCoverage: number | null;
}

export interface ComplianceFrameworkRef {
  framework: string;
  controls: string[];
}

export interface ComplianceResourceRef {
  uid: string | null;
  name: string | null;
  type: string | null;
  region: string | null;
  detail: string | null;
  status: ComplianceStatus;
}

export interface ComplianceOwner {
  name: string | null;
  email: string | null;
  team: string | null;
}

export interface ComplianceExceptionRef {
  id: number;
  status: string;
  justification: string | null;
  compensatingControl: string | null;
  requestedBy: string | null;
  approvedBy: string | null;
  expiresAt: string | null;
  daysUntilExpiry: number;
}

export interface ComplianceRemediationRef {
  id: number;
  status: string;
  assigneeName: string | null;
  assigneeEmail: string | null;
  ticketRef: string | null;
  dueAt: string | null;
  overdue: boolean;
}

export interface ComplianceControl {
  /** Scanner event_code, e.g. "iam_root_mfa_enabled". The identity of a control. */
  checkId: string;
  title: string | null;
  service: string | null;
  severity: ComplianceSeverity;
  status: ComplianceStatus;
  passedResources: number;
  failedResources: number;
  remediation: string | null;
  remediationRefs: string[];
  frameworks: ComplianceFrameworkRef[];
  /** Failing/undecided resources only. Passing resources are counted, not listed. */
  resources: ComplianceResourceRef[];
  owner: ComplianceOwner | null;
  exception: ComplianceExceptionRef | null;
  task: ComplianceRemediationRef | null;
  newlyFailing: boolean;
  failingForScans: number;
}

export interface ComplianceTrendPoint {
  scanId: number;
  at: string;
  score: number | null;
  passedControls: number;
  failedControls: number;
  critical: number;
  high: number;
  medium: number;
  low: number;
}

export interface ComplianceProgramHealth {
  activeExceptions: number;
  exceptionsExpiringSoon: number;
  lapsedExceptions: number;
  pendingExceptions: number;
  failingControlsWithOwner: number;
  failingControlsWithoutOwner: number;
  remediationOpen: number;
  remediationInProgress: number;
  remediationAwaitingVerification: number;
  remediationOverdue: number;
  completedScans: number;
  previousScanAt: string | null;
}

export interface ComplianceReport {
  accountId: string;
  provider: string | null;
  scanId: number | null;
  scannedAt: string | null;
  /** COMPLETED | FAILED | NONE */
  scanStatus: string;
  scanError: string | null;
  regions: string[];
  frameworksRequested: string[];
  scannerVersion: string | null;
  /**
   * The headline: unweighted share of settled controls that pass. Null when not assessable.
   *
   * Unweighted deliberately — frameworks do not rank their own controls, so severity travels
   * separately as `riskWeightedScore` instead of being folded in here.
   */
  score: number | null;
  /** Severity-weighted pass rate. A risk indicator, labelled as such. Never the headline. */
  riskWeightedScore: number | null;
  /** `score` with waived controls counted as met — the auditor's met-with-exception reading. */
  scoreWithExceptions: number | null;
  /** Percentage of the scan's control set that reached a settled outcome. */
  coverage: number | null;
  /** Whether coverage clears the floor required to publish a score at all. */
  coverageAdequate: boolean;
  assessable: boolean;
  scoreLabel: string;
  scoreMethod: string | null;
  counts: ComplianceCounts;
  frameworks: ComplianceFrameworkScore[];
  controls: ComplianceControl[];
  topRisks: ComplianceControl[];
  trend: ComplianceTrendPoint[];
  program: ComplianceProgramHealth;
  /** Caveats about this specific report, rendered verbatim. */
  notes: string[];
}

export interface ComplianceScanRecord {
  id: number;
  cloudAccountId: string;
  provider: string;
  status: string;
  startedAt: string;
  completedAt: string | null;
  regions: string | null;
  frameworks: string | null;
  prowlerVersion: string | null;
  totalFindings: number;
  passedFindings: number;
  failedFindings: number;
  errorMessage: string | null;
  score: number | null;
}

/** Statuses that represent outstanding work, as opposed to a settled outcome. */
export const isOpenControl = (status: ComplianceStatus): boolean =>
  status === 'FAIL' || status === 'MANUAL';
