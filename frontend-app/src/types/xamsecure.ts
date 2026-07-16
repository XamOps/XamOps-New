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

export interface VulnWorkloadCount {
  type: string;
  count: number;
}

export interface FunnelStage {
  label: string;
  value: number;
  available: boolean;
}

export interface VulnerabilityDashboard {
  totalCves: number;
  totalFindings: number;
  severityCounts: Record<string, number>;
  byWorkloadType: VulnWorkloadCount[];
  exploitableCves: number;
  epssPrioritizedCves: number;
  newLast7Days: Record<string, number>;
  overTime: Record<string, number>;
  severityOverTime: Array<Record<string, number | string>>;
  criticalWorkloadOverTime: Array<Record<string, number | string>>;
  workloadLabels: string[];
  funnel: FunnelStage[];
}

/** One row in the group-by-CVE Findings view. */
export interface CveGroupRow {
  cveId: string;
  name: string | null;
  severity: Severity | null;
  cvssScore: number | null;
  epssScore: number | null;
  exploitable: boolean;
  findingsCount: number;
  resourceCount: number;
  firstSeen: string | null;
  lastScan: string | null;
}

/** Drill-down row: one per affected RESOURCE for a CVE (packages summarised). */
export interface CveResourceFinding {
  resourceName: string | null;
  resourceType: string | null;
  region: string | null;
  cloudProvider: string | null;
  cvssScore: string | null;
  exploitable: boolean | null;
  firstSeenTime: string | null;
  lastScanTime: string | null;
  packageName?: string | null;     // single package name, or "N packages"
  packageCount?: number | null;
  packageVersion?: string | null;
  inUse?: boolean | null;
  image?: string | null;
  fixAvailable?: boolean | null;
  fixedInVersion?: string | null;
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

export interface InventoryAsset {
  id: number;
  upwindId: string;
  assetCategory: string | null;
  assetSubCategory: string | null;
  label: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  cloudResourceId: string | null;
  name: string | null;
  region: string | null;
  status: string | null;
  rawPayload: string | null;
}

export interface InventoryCategory {
  category: string | null;
  count: number;
}

export interface NetworkNode {
  subCategory: string;
  count: number;
}

export interface NetworkTopology {
  accountId: string | null;
  accounts: number;
  nodes: NetworkNode[];
}

export interface ApiEndpoint {
  id: string;
  method: string | null;
  uri: string | null;
  resourceId: string | null;
  firstSeenTime: string | null;
  lastSeenTime: string | null;
  internetExposed: boolean;
  cloudAccountId: string | null;
}

export interface ThreatPolicy {
  id: string;
  displayName: string | null;
  category: string | null;
  scope: string | null;
  openIssues: number;
  managedBy: string | null;
  enabled: boolean;
}

export interface Workflow {
  id: string;
  name: string | null;
  type: string | null;
  status: string | null;
  lastExecutionTime: string | null;
  cron: string | null;
  actions: string[];
}

export interface SbomComponent {
  name: string | null;
  version: string | null;
  packageManager: string | null;
  framework: string | null;
  licenses: string[];
  cloudProvider: string | null;
  cloudAccountId: string | null;
  critical: number;
  high: number;
  medium: number;
  low: number;
  totalVulns: number;
  inUse: number;
  resourceCount: number;
  affectedImages: number;
}

export interface ComplianceFramework {
  framework: string;
  failCount: number;
  passCount: number;
  /** Enriched from Upwind's configuration-frameworks endpoint. */
  version?: string | null;
  status?: string | null;
  lastScanTime?: string | null;
  score?: number | null;
}

/** Configurations "Dashboard" sub-view — all derived from real synced posture findings
 *  and real per-framework compliance scores. */
export interface ConfigurationsDashboard {
  severityCounts: Record<string, number>;
  totalFindings: number;
  newLast7Days: number;
  topFindings: IssueSummary[];
  findingsOverTime: Array<Record<string, number | string>>;
  frameworkScores: ComplianceFramework[];
  avgComplianceScore: number | null;
  frameworkCount: number;
}

/** One row in the Issues view — a posture check collapsed across all affected resources. */
export interface IssueSummary {
  check: string;
  title: string;
  severity: Severity;
  resourceCount: number;
  frameworks: string[];
  firstSeen: string | null;
  lastEvaluation: string | null;
  /** Attack Surface only: resource kind + ASM impact classification. */
  resourceKind?: string | null;
  impact?: string | null;
}

export interface ExposedTypeCount {
  type: string;
  count: number;
}

export interface ExposedLocation {
  country: string;
  coordinates: [number, number];
  count: number;
}

/** Attack Surface dashboard — exposure findings derived from posture data. */
export interface AttackSurfaceSummary {
  exposedResourceCount: number;
  exposedByType: ExposedTypeCount[];
  exposedByLocation: ExposedLocation[];
  severityCounts: Record<string, number>;
  findings: IssueSummary[];
}

export interface IssueResource {
  resourceId: string | null;
  resourceName: string | null;
  resourceType: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  region: string | null;
  severity: Severity | null;
  lastSeenTime: string | null;
}

export interface IssueDetail {
  title: string;
  severity: Severity | null;
  description: string | null;
  remediation: string | null;
  frameworks: string[];
  firstSeen: string | null;
  lastEvaluation: string | null;
  resourceCount: number;
  resources: IssueResource[];
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

export interface ThreatStory {
  id: string | null;
  title: string | null;
  severity: Severity | null;
  status: string | null;
  resourceName: string | null;
}

export interface ThreatStoriesSummary {
  activeCount: number;
  stories: ThreatStory[];
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
