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
  score: number | null;
  assessable: boolean;
  critical: number;
  high: number;
  medium: number;
  low: number;
  frameworkControlCount: number;
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
  score: number | null;
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
