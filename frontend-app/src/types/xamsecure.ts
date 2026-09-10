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
  /** by-CVE only: totals before any view filter, so a toggle can be labelled with its count. */
  totalCveCount?: number;
  runtimeExposedCount?: number;
  view?: 'all' | 'runtime';
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
  /**
   * Runtime narrowing: how the installed total reduces to what is actually reachable and
   * executing. Counts are distinct CVEs. Previously unavailable because the sync discarded
   * package.in_use, internet_exposure.ingress.* and exploitable_functions_in_use.
   */
  runtimeExposure?: {
    internetExposed: number;
    exposedAndInUse: number;
    activeCommunication: number;
    functionsInUse: number;
    packageInUse: number;
  } | null;
}

/** One row in the group-by-CVE Findings view. */
export interface CveGroupRow {
  cveId: string;
  name: string | null;
  severity: Severity | null;
  cvssScore: number | null;
  epssScore: number | null;
  exploitable: boolean;
  /** Distinct container images affected by this CVE. */
  imageCount: number;
  /** Runtime reachability, OR-ed across the CVE's findings. */
  packageInUse: boolean;
  internetExposed: boolean;
  functionsInUse: boolean;
  /** packageInUse && internetExposed — what Upwind calls a runtime-exposure vulnerability. */
  runtimeExposed: boolean;
  findingsCount: number;
  resourceCount: number;
  firstSeen: string | null;
  lastScan: string | null;
}

/**
 * One image / package / resource with its vulnerability rollup — the group-by dimensions
 * Upwind offers next to CVE. Severity is the worst in the group; runtime flags are OR-ed,
 * because one reachable member is enough to make the group worth attention.
 */
export interface VulnGroupRow {
  key: string;
  /** Image tag, package version, or resource type depending on the dimension. */
  secondary: string | null;
  severity: Severity | null;
  cveCount: number;
  findingsCount: number;
  resourceCount: number;
  exploitable: boolean;
  packageInUse: boolean;
  internetExposed: boolean;
  runtimeExposed: boolean;
}

export type VulnGroupDimension = 'image' | 'package' | 'resource';

export interface VulnGroupPage extends PageResp<VulnGroupRow> {
  dimension: VulnGroupDimension;
}

/** One threat / resource / account with its detection rollup. */
export interface ThreatGroupRow {
  key: string;
  secondary: string | null;
  severity: Severity | null;
  detections: number;
  occurrences: number;
  resourceCount: number;
  accountCount: number;
  firstSeenTime: string | null;
  lastSeenTime: string | null;
}

export type ThreatGroupDimension = 'threat' | 'resource' | 'account';

export interface ThreatGroups {
  dimension: ThreatGroupDimension;
  groups: ThreatGroupRow[];
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
  /** Base-image OS, for the distro mark in the drill-down. Null on host findings. */
  imageOsName?: string | null;
  imageOsVersion?: string | null;
  packageCount?: number | null;
  packageVersion?: string | null;
  inUse?: boolean | null;
  image?: string | null;
  imageTag?: string | null;
  /** Kubernetes context, present when the resource is a workload. */
  namespace?: string | null;
  clusterName?: string | null;
  /** Runtime exposure of the hosting resource. */
  internetExposed?: boolean | null;
  activeCommunication?: boolean | null;
  /** Upwind observed the vulnerable functions actually executing. */
  functionsInUse?: boolean | null;
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
  /** network_risk.internet_exposure_type, e.g. "wide". The only populated network_risk field. */
  internetExposureType: string | null;
  /**
   * network_risk.last_communication_with_internet_time. Mapped, but empty on every asset
   * measured 2026-08-11 — treat a value here as a bonus rather than something to rely on.
   */
  lastInternetCommunication: string | null;
  /** Comma-joined. Presence of a public IP is itself an exposure signal. */
  privateIpAddresses: string | null;
  publicIpAddresses: string | null;
  /** Which Upwind mechanism covers this asset (sensor, cloud scanner...). */
  protectedBy: string | null;
  /** Cloud provider tags, comma-joined. */
  cloudTags: string | null;
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

/** Sensor coverage for one workload type within an account. */
export interface CoverageWorkloadType {
  subCategory: string;
  total: number;
  sensor: number;
  cloudScanner: number;
  /** Explicitly reported as unprotected. */
  unprotected: number;
  /** No protection verdict was reported — unknown, not uncovered. */
  unknown: number;
}

export type CoverageStatus = 'FULL' | 'PARTIAL' | 'MINIMAL' | 'NO_SENSOR' | 'NO_WORKLOADS';

export interface SensorCoverageAccount {
  cloudAccountId: string;
  accountName: string;
  /** Compute assets — the things a runtime sensor can protect. */
  workloads: number;
  sensorProtected: number;
  cloudScannerOnly: number;
  unprotected: number;
  unknown: number;
  totalAssets: number;
  /** Assets with no sensor to run (storage, identity, and so on). */
  nonWorkloadAssets: number;
  /** Over workloads only. Null when there are no workloads to measure. */
  coveragePercent: number | null;
  status: CoverageStatus;
  workloadTypes: CoverageWorkloadType[];
}

/**
 * Runtime coverage per account.
 *
 * Distinct from onboarding status on purpose: an account can be CONNECTED and have zero sensors,
 * which means its configuration is scanned but nothing watches its workloads at runtime.
 */
export interface SensorCoverage {
  accounts: SensorCoverageAccount[];
}

/** One asset in the configuration graph. */
export interface TopologyNode {
  /** Upwind asset id (uwr-…), or `vpc:<id>` / `subnet:<id>` when synthetic. */
  id: string;
  /** Upwind asset label, e.g. aws_ec2_instance. */
  type: string | null;
  name: string;
  category?: string | null;
  subCategory?: string | null;
  region?: string | null;
  cloudAccountId?: string | null;
  /** Canonical (join-safe) resource id — see UpwindAssetIds on the backend. */
  resourceId?: string | null;
  cidrBlock?: string | null;
  /** Upwind's internet_exposure_type, e.g. "wide". Null when not exposed or not analysed. */
  exposure?: string | null;
  /**
   * Inferred rather than inventoried. True for every subnet (Upwind does not model subnets as
   * assets) and for VPCs referenced from outside the accounts we walked.
   */
  synthetic?: boolean;
}

export type TopologyEdgeKind = 'in-vpc' | 'in-subnet' | 'attached-to';

export interface TopologyEdge {
  source: string;
  target: string;
  kind: TopologyEdgeKind;
}

/**
 * Configuration topology — provider-declared containment, NOT observed traffic.
 *
 * Upwind's Runtime map is sensor telemetry (direction, ports, protocol, volume, a 24h timeline)
 * and no API endpoint serves it, so this graph deliberately shows something narrower: what
 * contains what. Do not label it as traffic in the UI.
 */
export interface ConfigGraph {
  nodes: TopologyNode[];
  edges: TopologyEdge[];
  /** The walk hit its time budget; the graph is incomplete. */
  truncated: boolean;
  source: 'configuration';
}

export interface NetworkTopology {
  accountId: string | null;
  accounts: number;
  /** Retained sub-category counts, kept so older consumers keep working. */
  nodes: NetworkNode[];
  graph?: ConfigGraph | null;
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

/**
 * A detection policy from either of Upwind's two sets.
 *
 * `kind` distinguishes them because they are separate lists, not versions of each other:
 * CLASSIC is the v1 /threat-policies list and POLICY is the v2 search. Upwind's console shows
 * both and counts them together, so reading only v1 under-reported the total.
 */
export interface ThreatPolicy {
  id: string;
  displayName: string | null;
  category: string | null;
  scope: string | null;
  openIssues: number;
  managedBy: string | null;
  enabled: boolean;
  kind: 'CLASSIC' | 'POLICY';
  /** v2 only. */
  severity: Severity | null;
  description: string | null;
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
  /**
   * Null means "not counted", not "nothing passes".
   *
   * The sync stopped storing PASS findings: they were 501k rows, the same check repeated once
   * per framework across 55 enabled frameworks, all to recompute a percentage Upwind publishes
   * itself as `score`. Prefer `score`; the framework drawer still shows real per-control
   * pass/fail, fetched live per framework.
   */
  passCount: number | null;
  /** Upwind's framework id. Absent for frameworks that only appear in posture findings. */
  frameworkId?: string | null;
  /** Enriched from Upwind's configuration-frameworks endpoint. */
  version?: string | null;
  status?: string | null;
  lastScanTime?: string | null;
  score?: number | null;
  /**
   * From the v2 frameworks search, e.g. `no_cloud_account_connected`, `during_first_scan`,
   * `no_finding_resources`. This is what separates "0% compliant" from "never evaluated here",
   * which a score alone cannot express.
   */
  state?: string | null;
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
  createTime: string | null;
  updateTime: string | null;
  /** Detections the story correlates, as Upwind counts them ("Related events"). */
  relatedEvents: number;
  /** How many of those we hold for the selected accounts. Always >= 1, or the story is withheld. */
  resolvedCount: number;
}

export interface ThreatStoriesSummary {
  activeCount: number;
  stories: ThreatStory[];
}

/** One day of the Threats trend chart. Bands below High are collapsed into `other`. */
export interface ThreatTrendPoint {
  date: string;
  critical: number;
  high: number;
  other: number;
}

/**
 * Threats dashboard aggregates.
 *
 * `eventCount` is null with `eventCountAvailable: false`: Upwind's funnel starts from raw
 * sensor events and we sync detections only, so the stage is shown unavailable rather than
 * filled with a different quantity under the same label.
 */
export interface ThreatOverview {
  detectionCount: number;
  resourceCount: number;
  storyCount: number;
  eventCount: number | null;
  eventCountAvailable: boolean;
  eventCountReason: string | null;
  bySeverity: Record<string, number>;
  trend: ThreatTrendPoint[];
  /** Days covered by `trend`, and the windows the API accepts. */
  trendDays: number;
  trendWindows: number[];
  /**
   * Detections whose last-seen falls inside the window. Needed to read the chart: a nearly
   * empty 7-day chart against 31 detections is stale data, not a broken chart, and only the
   * ratio distinguishes the two.
   */
  detectionsInWindow: number;
  byTactic: { tactic: string; count: number }[];
  bySource: { source: string; count: number }[];
}

/** One detection correlated into a threat story. */
export interface StoryDetection {
  id: string | null;
  title: string | null;
  severity: Severity | null;
  status: string | null;
  category: string | null;
  resourceId: string | null;
  resourceName: string | null;
  resourceType: string | null;
  cloudAccountId: string | null;
  mitreTactics: string[];
  occurrenceCount: number | null;
  firstSeenTime: string | null;
  lastSeenTime: string | null;
}

/**
 * A threat story in full. `description` is Upwind's own markdown incident narrative and
 * carries their inline markup ([br], [header_with_icon ...], [link text=".." href=".."]),
 * so it needs the dedicated renderer rather than a plain markdown component.
 *
 * `detectionCount` is what the story references; `resolvedCount` is how many of those we
 * hold locally for the selected accounts — they differ when a story spans accounts the
 * caller doesn't own, or when detections predate our sync.
 */
export interface ThreatStoryDetail {
  id: string | null;
  title: string | null;
  severity: Severity | null;
  status: string | null;
  createTime: string | null;
  updateTime: string | null;
  description: string | null;
  detectionCount: number;
  resolvedCount: number;
  detections: StoryDetection[];
  worstSeverity: Severity | null;
  firstSeenTime: string | null;
  lastSeenTime: string | null;
  mitreTactics: { tactic: string; count: number }[];
  categories: { category: string; count: number }[];
  resources: {
    resourceId: string | null;
    resourceName: string | null;
    resourceType: string | null;
    cloudProvider: string | null;
    cloudAccountId: string | null;
    detectionCount: number;
  }[];
}

/**
 * One compliance control within a framework.
 *
 * `compliancePercent` is null when the control has no findings at all — Upwind's "no relevant
 * assets were found in your environment". That is not the same as 0%, and conflating them would
 * make an unscanned control look like a total failure.
 */
export interface FrameworkControl {
  ruleId: string;
  title: string | null;
  severity: Severity | null;
  passCount: number;
  failCount: number;
  assetCount: number;
  compliancePercent: number | null;
  /**
   * Findings whose status is neither pass nor fail. Zero on every response measured; carried so
   * an unrecognised status shows up as unscored rather than silently counting as a failure.
   */
  unscoredCount?: number;
  resourceCount: number;
  remediation: string | null;
  lastEvaluated: string | null;
}

export interface FrameworkControls {
  frameworkId: string;
  controls: FrameworkControl[];
  controlCount?: number;
  /** Findings dropped because they belong to another account. */
  skippedOtherAccounts?: number;
}

/**
 * Provider-native relationships for one asset, e.g. SecurityGroups -> ["sg-0df6..."].
 *
 * Per asset only. The v2 relationship search does traverse the graph, but it filters by
 * relationship without returning which related entity matched, so one asset's own neighbours
 * still have to come from the asset-detail payload.
 */
export interface AssetRelationship {
  field: string;
  label: string;
  references: string[];
  count: number;
  /**
   * Asset label this points at (e.g. aws_security_group), from Upwind's inventory schema.
   * Null when the provider-native key has no schema counterpart — "type unknown", not
   * "not a relationship".
   */
  relatedType?: string | null;
}

export interface AssetRelationships {
  assetId: string;
  resourceType?: string | null;
  relationships: AssetRelationship[];
  /** How many relationships the schema declares for this asset type. */
  declaredEdgeCount?: number;
}

/** One CI build from Upwind's shift-left stream. */
export interface CodeScan {
  /** Absent on ~11% of scans; without it findings cannot be fetched, so the row is history only. */
  fingerprint: string | null;
  type: string | null;          // IMAGE_BUILD | IAC_SCAN_BUILD
  source: string | null;        // GITHUB
  repository: string | null;
  branch: string | null;
  commitSha: string | null;
  initiator: string | null;
  image: string | null;
  imageOs: string | null;
  mainBranchName: string | null;
  pullRequestId: string | null;
  pullRequestUrl: string | null;
  createTime: string | null;
  updateTime: string | null;
}

/**
 * Shift-left scans belong to a source repository, not a cloud account, and every client reads one
 * shared Upwind organization. Nothing in the payload identifies a tenant — `image` is the build
 * name, not a runtime image — so visibility is configured rather than inferred, and `available` is
 * false with a reason for any client that did not connect the repository.
 */
export interface CodeScansResponse {
  /** Newest first, capped at the requested limit. */
  scans: CodeScan[];
  available: boolean;
  unavailableReason?: string | null;
  /** How many scans the backend read from Upwind before keeping the newest `scans.length`. */
  scansRead?: number;
  /**
   * True when older scans exist that this payload does not include. Upwind's search has no sort
   * and a broken date filter, so the backend pages the stream and keeps the newest N — anything
   * beyond that is absent, and the UI must not present the result as complete history.
   */
  historyTruncated?: boolean;
}

/** A CVE as reported against a build, richer than our runtime rows: carries the fix version. */
export interface CodeCve {
  cveId: string | null;
  name: string | null;
  description: string | null;
  severity: Severity | null;
  score: string | null;
  cvssVector: string | null;
  packageName: string | null;
  packageVersion: string | null;
  fixAvailable: boolean;
  fixedInVersion: string | null;
  exploitable: boolean;
  status: string | null;
}

/**
 * `introduced` is what this build ADDS relative to its baseline — the question runtime
 * vulnerability data cannot answer, since that describes what is already deployed.
 */
export interface CodeScanFindings {
  fingerprint: string | null;
  initiator: string | null;
  branch: string | null;
  imageName: string | null;
  imageOs: string | null;
  cves: {
    highImpact: CodeCve[];
    introduced: CodeCve[];
    noChange: CodeCve[];
    resolved: CodeCve[];
  };
  counts: { highImpact: number; introduced: number; noChange: number; resolved: number };
}

export interface OnboardingAccount {
  accountName: string;
  cloudAccountId: string;
  provider: string;
  status: 'NOT_CONNECTED' | 'PENDING' | 'CONNECTED';
  sensorInstallCommand: string | null;
  /**
   * Whether a Connect button should be offered: AWS, AND the client is entitled.
   * Folds both conditions so the UI cannot offer an action that will 403.
   */
  selfServiceSupported: boolean;
  /**
   * Whether the client is entitled at all, controlled from BillOps admin. Separate from
   * `selfServiceSupported` so the UI can distinguish "not entitled" from "provider unsupported",
   * which need different explanations.
   */
  onboardingAllowed: boolean;
  /** When a launch URL was last issued — anchors "waiting" vs "the stack probably failed". */
  stackRequestedAt: string | null;
  stackName: string | null;
  stackRegion: string | null;
  /** When Upwind first reported assets, i.e. proof the connection works. */
  connectionVerifiedAt: string | null;
}

/**
 * Response of POST /api/xamsecure/onboarding/aws/launch-url.
 *
 * `launchUrl` embeds Xammer's shared Upwind client secret as a stack parameter, so it is a
 * credential: open it, never log it, never store it, never put it in a link the user can share.
 */
export interface UpwindLaunchUrl {
  launchUrl: string;
  stackName: string;
  stackRegion: string;
  cloudAccountId: string;
  status: OnboardingAccount['status'];
}

/** Response of POST /api/xamsecure/onboarding/aws/check. */
export interface UpwindConnectionCheck {
  cloudAccountId: string;
  status: OnboardingAccount['status'];
  /** Data is actually flowing (assets present). */
  reportingAssets: boolean;
  /** Registered upstream — authoritative, and true before the first scan produces assets. */
  registered: boolean;
  /** Null when unknown. False means registered but producing no findings. */
  scannerEnabled: boolean | null;
  verifiedAt: string | null;
  message: string;
}

/** Response of POST /api/xamsecure/onboarding/aws/disconnect. */
export interface UpwindDisconnectResult {
  cloudAccountId: string;
  status: OnboardingAccount['status'];
  /** Whether the registration was actually removed upstream. */
  deregistered: boolean;
  /** The stack the customer still has to delete themselves. */
  stackName: string | null;
  stackRegion: string | null;
  message: string;
}

export const SEVERITY_ORDER: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];

export function severityTotal(m: Record<string, number> | undefined): number {
  if (!m) return 0;
  return Object.values(m).reduce((a, b) => a + b, 0);
}
