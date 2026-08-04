export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | string;

export interface PageResp<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  /**
   * Severity distribution across the whole result set, independent of the current page or
   * active filter. Present on endpoints that back group-by chips (currently by-CVE).
   */
  severityCounts?: Record<string, number>;
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
  /** Cloud resource id — the key the resource drawer joins every domain on. */
  resourceId: string | null;
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
  resourceId: string | null;
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
  /** Cloud resource id — the key the resource drawer joins every domain on. */
  resourceId: string | null;
  resourceName: string | null;
  resourceType: string | null;
  /**
   * Kubernetes context, present only when the finding's resource is a workload rather than
   * a cloud resource. Available from v1 all along; we simply weren't mapping it.
   */
  clusterId: string | null;
  namespace: string | null;
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
  // From Upwind's inventory CATALOG asset. null means Upwind didn't report the signal,
  // which is deliberately distinct from false.
  hasElevatedPrivilege: boolean | null;
  sensitivePiiAtRest: boolean | null;
  sensitivePhiAtRest: boolean | null;
  sensitivePciAtRest: boolean | null;
  sensitivePiiInTransit: boolean | null;
  sensitivePhiInTransit: boolean | null;
  sensitivePciInTransit: boolean | null;
  vulnCriticalCount: number | null;
  vulnHighCount: number | null;
  vulnTotalCount: number | null;
  detectionTotalCount: number | null;
  internetExposureType: string | null;
  technologies: string | null;
  hasEolTechnology: boolean | null;
}

/** Classification / privilege / EOL / exposure rollup over the synced asset catalog. */
export interface InventoryRisk {
  assets: number;
  pii: number;
  phi: number;
  pci: number;
  elevatedPrivilege: number;
  eolTechnology: number;
  internetExposed: number;
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
  authState: string | null;
  authenticated: boolean;
  domains: string[];
  cloudAccountId: string | null;
  /** "CATEGORY:Type" entries observed on the endpoint, e.g. "PII:Email". */
  sensitiveData: string[];
  hasSensitiveData: boolean;
  /** Observed HTTP status codes, e.g. ["200", "401"]. */
  statusCodes: string[];
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
  /** Per-action delivery detail, shown in the workflow detail panel. */
  actionDetails?: { type: string | null; targetAudience: string | null }[];
  /** e.g. WEEKLY_REPORT — what the scheduled run produces. */
  reportType?: string | null;
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

/** Per-domain sync freshness, so stale data is visible instead of silently rendered. */
export interface SyncDomainStatus {
  domain: string;
  lastSuccessAt: string | null;
  ageSeconds: number | null;
  stale: boolean;
  failing: boolean;
  lastError: string | null;
}

export interface SyncStatus {
  status: 'OK' | 'STALE' | 'ERROR';
  oldestSuccessAt: string | null;
  serverTime: string;
  domains: SyncDomainStatus[];
}

/**
 * Identities that have failing configuration checks. Derived from posture findings —
 * The roster is every identity in Upwind's inventory catalog — IAM users, roles, groups and
 * policies plus Kubernetes service accounts, roles and bindings — annotated with failing
 * checks and Upwind's own high_privilege_risk flag. Upwind's CIEM module (entitlement
 * analysis, human vs non-human split, role-trust graph) has no API and is absent.
 */
export interface IdentityRow {
  resourceId: string | null;
  name: string | null;
  resourceType: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  severity: Severity | null;
  findingCount: number;
  checks: string[];
  region: string | null;
  firstSeen: string | null;
  lastSeen: string | null;
  /** Upwind flagged the underlying asset as holding elevated privilege. */
  elevatedPrivilege: boolean;
  /** False for identities that fail a check but are absent from the latest inventory walk. */
  inInventory: boolean;
}

/** An asset Upwind flagged as privileged, whether or not it currently fails a check. */
export interface PrivilegedIdentity {
  resourceId: string | null;
  name: string | null;
  resourceType: string | null;
  category: string | null;
  cloudProvider: string | null;
  cloudAccountId: string | null;
  region: string | null;
  internetExposureType: string | null;
  sensitivePii: boolean;
  hasFindings: boolean;
}

export interface IdentitiesSummary {
  identities: IdentityRow[];
  severityCounts: Record<string, number>;
  byType: { type: string; count: number }[];
  total: number;
  /** Identities with at least one failing check; the rest are clean. */
  withFindings: number;
  privilegedTotal: number;
  privileged: PrivilegedIdentity[];
}

/** One resource joined across every synced domain that references it. */
export interface ResourceDetail {
  identity: {
    resourceId: string;
    name?: string | null;
    resourceType?: string | null;
    assetCategory?: string | null;
    cloudProvider?: string | null;
    cloudAccountId?: string | null;
    region?: string | null;
  };
  counts: {
    configurations: number;
    vulnerabilities: number;
    detections: number;
  };
  topSeverity: Severity | null;
  configurations: {
    check: string | null;
    severity: Severity | null;
    framework: string | null;
    lastSeenTime: string | null;
  }[];
  vulnerabilities: {
    cveId: string | null;
    name: string | null;
    severity: Severity | null;
    cvssScore: string | null;
    epssScore: string | null;
    exploitable: boolean | null;
    lastScanTime: string | null;
  }[];
  detections: {
    upwindId: string;
    title: string | null;
    severity: Severity | null;
    status: string | null;
    mitreTactics: string | null;
    lastSeenTime: string | null;
  }[];
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
