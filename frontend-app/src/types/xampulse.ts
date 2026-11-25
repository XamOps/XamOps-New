/**
 * XamPulse (Observability 1.0) API contract. Mirrors impl-plan.md section 2 and the Java records in
 * com.xammer.cloud.xampulse.dto.*. Field names must stay identical on both sides.
 */
export interface XpErrorEnvelope { error: { code: XpErrorCode; message: string; requestId: string; details?: Record<string, unknown> } }
export type XpErrorCode =
  | 'XP_INVALID_REQUEST' | 'XP_BAD_FILTER' | 'XP_QUERY_TOO_BROAD' | 'XP_NOT_FOUND' | 'XP_NOT_SET_UP'
  | 'XP_STATE_CONFLICT' | 'XP_BUCKET_UNUSABLE' | 'XP_STACK_FAILED' | 'XP_RATE_LIMITED' | 'XP_QUOTA_EXCEEDED'
  | 'XP_STORE_UNAVAILABLE' | 'XP_AWS_UNAVAILABLE' | 'XP_AI_DISABLED' | 'XP_QUERY_TIMEOUT' | 'XP_FORBIDDEN';

export type XpSeverity = 'TRACE' | 'DEBUG' | 'INFO' | 'WARN' | 'ERROR' | 'FATAL';
export type XpAccountState = 'NOT_SET_UP' | 'PLANNED' | 'DEPLOYING' | 'VERIFYING' | 'CONNECTED' | 'DEGRADED' | 'DISCONNECTED';
export type XpHealth = 'OK' | 'WARN' | 'CRIT' | 'NO_DATA';

export interface XpScope {
  accountIds: number[];
  regions?: string[];
  from: string;
  to: string;
  resource?: { instanceIds?: string[]; services?: string[]; containers?: string[] };
}
export interface XpAccountRef { cloudAccountId: number; awsAccountId: string; name: string; state: XpAccountState; demo: boolean }
export interface XpPage<T> { items: T[]; page: number; size: number; total: number }
export interface XpStatus { enabled: boolean; demoAvailable: boolean }

// ---- overview, hosts, events, usage ----
export interface XpEvidence { kind: 'METRIC' | 'LOG' | 'EVENT' | 'ALERT'; label: string; deepLink: string }
export interface XpAttentionItem {
  id: string; severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW'; title: string; detail: string;
  accountId: number; instanceId?: string; since: string; confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  evidence: XpEvidence[];
}
export interface XpHeatTile { accountId: number; instanceId: string; name: string; health: XpHealth; score: number | null; cpuPct: number | null; memPct: number | null }
export interface XpOverview {
  demo: boolean; state: XpAccountState; lastDataAt: string | null;
  healthScore: number | null;
  kpis: { hostsReporting: number; hostsTotal: number; errorsPerMin: number; ingestBytes24h: number; openIncidents: number; coveragePct: number };
  narrative: string[];
  attention: XpAttentionItem[];
  tiles: XpHeatTile[];
}
export type XpAgentStatus = 'ACTIVE' | 'PENDING' | 'STALE' | 'REVOKED' | 'NONE';
export type XpCollectionLevel = 'T0' | 'T1' | 'T2';
export interface XpHostRow {
  accountId: number; instanceId: string; name: string; region: string; platform: string; instanceType?: string;
  state: string; health: XpHealth; score: number | null; reason?: string;
  cpuPct: number | null; memPct: number | null; diskPct: number | null; netInBps: number | null; netOutBps: number | null; load1: number | null;
  cpuSpark: number[]; lastSeenAt: string | null; agentStatus: XpAgentStatus;
  collectionLevel: XpCollectionLevel; tags: Record<string, string>;
}
export interface XpLane { lane: 'CPU' | 'MEMORY' | 'DISK' | 'NETWORK' | 'ERRORS' | 'FRESHNESS'; health: XpHealth; reason: string }
export interface XpEvent { ts: string; kind: 'DEPLOY' | 'EC2_STATE' | 'ALERT' | 'ANOMALY' | 'AGENT' | 'CONFIG'; accountId: number; resource: string; title: string; detail?: string }
export interface XpHostSummary {
  host: XpHostRow;
  lanes: XpLane[];
  series: Record<string, [number, number][]>;
  recentErrors: XpLogRow[]; events: XpEvent[]; openIncidents: XpIncident[];
}
export interface XpProcessRow { pid: number; name: string; user?: string; cpuPct: number; memPct: number; memBytes?: number; threads?: number }
/**
 * Throttle Radar for one host. Each finding is a limit that was measured to be hit in the range, with the numbers
 * behind it (shares are 0..1, *_pct already percent). A signal is OK (measured, nothing hit), LIMITED (a finding) or
 * NOT_MEASURED (this host cannot provide it; note says why). NOT_MEASURED is never shown as healthy.
 */
export interface XpLimitFinding {
  severity: 'CRITICAL' | 'WARNING' | 'INFO'; code: string; source: 'AWS' | 'KERNEL' | 'CONTAINER';
  title: string; detail: string; fix: string; evidence: Record<string, number>;
}
export interface XpLimitSignal { code: string; label: string; state: 'OK' | 'LIMITED' | 'NOT_MEASURED'; note?: string }
export interface XpHostLimits { instanceId: string; from: string; to: string; findings: XpLimitFinding[]; signals: XpLimitSignal[] }

/**
 * Change Intelligence: something that changed on a host and what the measurements did right after. Effects say what
 * FOLLOWED a change, never that it caused it; `overlapping` means another change happened within 10 minutes.
 */
export type XpChangeKind = 'DEPLOY' | 'RECREATE' | 'CONTAINER_START' | 'CONTAINER_STOP' | 'RESTART' | 'REBOOT' | 'DATA_GAP'
  | 'PACKAGE' | 'CONFIG' | 'EC2_STATE' | 'AGENT';
export interface XpChangeEffect { signal: string; label: string; unit: string; before: number; after: number; relative: number; strength: 'STRONG' | 'MODERATE' }
export interface XpChange {
  ts: string; instanceId: string; kind: XpChangeKind | string; title: string; detail: string; source: 'AGENT' | 'LOGS' | 'PLATFORM' | string;
  subject?: string; verdict?: 'FOLLOWED_BY_CHANGE' | 'NO_CLEAR_EFFECT' | 'TOO_RECENT' | 'NOT_ENOUGH_DATA'; overlapping: boolean; effects: XpChangeEffect[];
}

/**
 * Fleet twin: the host against its peers (same Auto Scaling group, else same type and name pattern). verdict HIGHER /
 * LOWER is an outlier (robust z >= 3.5 and a real difference); indicative when only two peers have a value.
 */
export interface XpPeerMetric {
  key: string; label: string; unit: string; value: number | null; peerMedian: number | null; peerMin: number | null; peerMax: number | null;
  z: number | null; verdict: 'HIGHER' | 'LOWER' | 'IN_LINE' | 'NO_DATA' | 'TOO_FEW'; indicative: boolean; peerValues: Record<string, number>;
}
export interface XpPeers { instanceId: string; basis: 'ASG' | 'TYPE_NAME' | 'NONE'; basisLabel?: string; peers: { instanceId: string; name?: string }[]; metrics: XpPeerMetric[]; note?: string }

/**
 * Exposure check: listening sockets (agent) against security group inbound rules (EC2). exposure: INTERNET, PUBLIC_RANGE,
 * PRIVATE, BLOCKED (no rule lets it in) or LOCAL (loopback). measured false: the agent does not report sockets yet.
 */
export interface XpExposureSocket { protocol: string; port: number; bind: string; address: string; service?: string; exposure: 'INTERNET' | 'PUBLIC_RANGE' | 'PRIVATE' | 'BLOCKED' | 'LOCAL'; via?: string }
export interface XpExposure { instanceId: string; publicIp?: string; measured: boolean; securityGroups: string[]; sockets: XpExposureSocket[]; findings: XpLimitFinding[]; note?: string }

/** Cost against use over the range. No price is never shown as $0: hourlyUsd is absent and a note says why. */
export interface XpCostPerf {
  instanceId: string; instanceType?: string; vcpus?: number; memoryGiB?: number; lifecycle?: string; platform?: string;
  hourlyUsd?: number; monthlyUsd?: number; priceBasis?: 'ON_DEMAND_LIST' | 'SPOT_CURRENT';
  cpuAvgPct?: number; cpuP95Pct?: number; memAvgPct?: number; memP95Pct?: number; usedVcpusAvg?: number; costPerUsedVcpuHour?: number;
  verdict: 'UNDERSIZED' | 'FITS' | 'OVERSIZED' | 'BURSTABLE' | 'NOT_ENOUGH_DATA'; verdictText: string; notes: string[];
}

/** Read-only runbooks over SSM: fixed scripts (shown as they run), audited, off unless the deployment turns them on. */
export interface XpRunbook { id: string; title: string; description: string; script: string }
export interface XpRunbookCatalog { enabled: boolean; runbooks: XpRunbook[] }
export interface XpRunbookStarted { commandId: string; runbook: string }
export interface XpRunbookResult {
  commandId: string; runbook: string; status: 'PENDING' | 'IN_PROGRESS' | 'SUCCESS' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED';
  output: string; error: string; truncated: boolean;
}

/** Investigate: each usual explanation checked against measurements. Never claims a cause. */
export interface XpHypothesis {
  code: string; title: string; status: 'SUPPORTED' | 'RULED_OUT' | 'NOT_MEASURABLE'; confidence?: 'HIGH' | 'MEDIUM' | 'LOW'; weight: number;
  evidence: string[]; section?: string; logsQuery?: string;
}
export interface XpInvestigation { instanceId: string; from: string; to: string; summary: string; hypotheses: XpHypothesis[]; peers?: XpPeers }

/** Container 360: one container (by name on one host) through every redeploy in the range. */
export interface XpContainerSummary {
  instanceId: string; name: string; containerId: string; image: string; firstSeen: string; lastSeen: string;
  memLimitBytes?: number | null; restarts?: number | null;
  versions: { containerId: string; image: string; firstSeen: string; lastSeen: string }[];
  /** cpu (% of one CPU), memory and memory_limit (bytes), throttled_pct, network_rx/tx and disk_read/write (bytes/s),
   *  uptime (seconds since start) and host_memory (the host's total bytes, one point). */
  series: Record<string, [number, number][]>;
  findings: XpLimitFinding[]; changes: XpChange[]; recentErrors: XpLogRow[];
}

export interface XpContainerRow {
  accountId: number; instanceId: string; containerId: string; name: string; image?: string;
  /** Percent of ONE CPU, as Docker reports it: 100 = one full core, so it can exceed 100 on multi-core hosts. */
  cpuPct?: number | null; memBytes?: number | null; memLimitBytes?: number | null; restarts?: number | null; lastSeenAt?: string | null;
  /** True when the reported limit is the host's memory (no limit set); null when the host's memory is unknown. */
  memLimitIsHost?: boolean | null;
  /** ERROR + FATAL log lines in the last 15 minutes. */
  errors?: number | null;
}
export interface XpUsage {
  range: string; byDay: { day: string; signal: 'LOGS' | 'METRICS'; bytesIn: number; bytesStored: number }[];
  bySource: { source: string; bytes: number; pct: number }[]; hotBytes: number; archiveBytes: number;
  projectedMonthlyCustomerAwsUsd: number | null; quotaGbPerDay: number | null;
}

// ---- logs ----
export interface XpLogRow {
  id: string; ts: string; accountId: number; region: string; instanceId: string; service: string; source: string;
  severity: XpSeverity; body: string; attrs: Record<string, string>; traceId?: string; containerId?: string;
}
export interface XpLogSearchRequest { scope: XpScope; query: string; sort?: 'TS_DESC' | 'TS_ASC'; limit?: number; cursor?: string }
export interface XpLogSearchResponse { rows: XpLogRow[]; nextCursor?: string; scanned: { rows: number; bytes: number }; tookMs: number; truncated: boolean; parsed?: { normalised: string } }
export interface XpLogHistogramRequest { scope: XpScope; query: string; buckets?: number }
export interface XpLogHistogramResponse { bucketSec: number; buckets: { t: string; counts: Partial<Record<XpSeverity, number>> }[] }
export interface XpLogFacetsRequest { scope: XpScope; query: string; fields: string[]; topN?: number }
export interface XpLogFacetsResponse { approx: boolean; facets: { field: string; values: { value: string; count: number }[] }[] }
export interface XpLogPatternsRequest { scope: XpScope; query: string; compareToPrevious?: boolean; limit?: number }
export interface XpLogPattern { patternId: string; template: string; count: number; deltaPct: number | null; isNew: boolean; trend: number[]; firstSeen: string; sampleId: string; sampleAccountId: number; severity: XpSeverity }
export interface XpLogPatternsResponse { patterns: XpLogPattern[] }
export interface XpLogContextResponse { before: XpLogRow[]; anchor: XpLogRow; after: XpLogRow[] }
export interface XpTailTicketRequest { scope: XpScope; query: string }
export interface XpTailTicket { ticket: string; url: string; expiresInSec: number }
export type XpTailServerFrame =
  | { t: 'rows'; rows: XpLogRow[]; cursor: string; rate: number }
  | { t: 'stats'; matched: number; dropped: number }
  | { t: 'notice'; code: 'RATE_CAPPED' | 'QUERY_REVALIDATED' | 'IDLE_WARNING'; message?: string }
  | { t: 'end'; reason: 'IDLE_TIMEOUT' | 'MAX_DURATION' | 'REVOKED' | 'ERROR' | 'CLIENT_CLOSED'; message?: string };
export type XpTailClientFrame = { t: 'pause' } | { t: 'resume' } | { t: 'ping' } | { t: 'query'; query: string };
export interface XpArchiveSearchRequest { scope: XpScope; query: string }
export interface XpArchiveEstimate { bytes: number; requests: number; usd: number; storageClasses: string[]; state: 'ESTIMATED' }

// ---- metrics ----
export type XpAgg = 'avg' | 'min' | 'max' | 'sum' | 'p50' | 'p95' | 'p99' | 'rate' | 'last';
export interface XpMetricFilter { k: string; op: '=' | '!=' | '=~'; v: string }
export interface XpMetricSeriesRequest { metric: string; agg: XpAgg; groupBy?: string[]; filters?: XpMetricFilter[]; stepSec?: number }
export interface XpMetricQueryRequest { scope: XpScope; series: XpMetricSeriesRequest[]; band?: 'ANOMALY' | null; compareOffsetSec?: number }
export interface XpMetricSeries { metric: string; labels: Record<string, string>; points: [number, number | null][]; band?: [number, number, number][]; compare?: [number, number | null][] }
export interface XpMetricQueryResponse { stepSec: number; series: XpMetricSeries[]; tookMs: number }
export interface XpMetricCatalogEntry { name: string; unit: string; kind: 'GAUGE' | 'COUNTER'; labelKeys: string[] }

// ---- alerts ----
export type XpConditionType = 'METRIC_THRESHOLD' | 'LOG_COUNT' | 'HEARTBEAT_MISSING' | 'METRIC_ANOMALY' | 'FORECAST';
export type XpRuleSeverity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW';
export interface XpAlertRule {
  id?: number; version?: number; name: string; enabled: boolean; severity: XpRuleSeverity;
  scope: { accountIds: number[]; instanceIds?: string[]; tag?: string };
  condition: {
    type: XpConditionType; metric?: string; filters?: XpMetricFilter[]; query?: string;
    agg?: 'avg' | 'min' | 'max' | 'sum' | 'p95' | 'count'; window: string; op: '>' | '>=' | '<' | '<='; value?: number;
    for: string; noData: 'ALERT' | 'OK' | 'KEEP'; forecastDays?: number;
  };
  grouping: { by: string[]; dedupeWindow: string };
  routing: { channelIds: number[]; createTicket: boolean; runbookUrl?: string };
}
/**
 * One firing in a backtest. from: first breaching evaluation; firedAt: when it fired (after "for"); to: recovery, or
 * the end of the range when ongoing. peak: worst value (highest for >, lowest for <; null when only missing data).
 * notified is false for a repeat inside the dedupe window and for a quick re-open, which alerting does not send either.
 */
export interface XpFiring { from: string; firedAt?: string; to: string; group: Record<string, string>; peak: number | null; ongoing?: boolean; notified?: boolean }
/** Alert backtest: the rule replayed once a minute with live alerting's queries, state machine and dedupe. */
export interface XpAlertPreview {
  firings: XpFiring[]; evaluatedPoints: number; wouldNotify: number;
  from?: string; to?: string; op?: string; threshold?: number; groups?: number; resolved?: number; suppressedRepeats?: number; quietReopens?: number;
  /** The value the rule compared with its threshold, minute by minute ([epochMs, value]), for the groups most worth seeing. */
  series?: { group: string; points: [number, number][] }[];
  notes?: string[];
}
export interface XpIncident {
  id: number; accountId: number; title: string; severity: XpRuleSeverity; state: 'OPEN' | 'ACKED' | 'SNOOZED' | 'RESOLVED';
  openedAt: string; resolvedAt?: string; ackBy?: string; ruleIds: number[]; instanceIds: string[]; summary: string;
  timeline: { ts: string; kind: string; text: string }[];
}
export interface XpIncidentAction { comment?: string; snoozeUntil?: string }
export interface XpSilence { id?: number; accountId: number; matcher: { instanceIds?: string[]; ruleIds?: number[]; severity?: string[] }; startsAt: string; endsAt: string; reason: string }
export interface XpChannelInput { id?: number; accountId: number; kind: 'EMAIL' | 'SLACK' | 'TEAMS' | 'WEBHOOK'; name: string; config: { to?: string[]; url?: string } }
export interface XpChannel { id: number; accountId: number; kind: XpChannelInput['kind']; name: string; configured: true; summary: string }

// ---- insights, health, trust ----
export interface XpHealthScore { score: number | null; lanes: XpLane[]; asOf: string; explanation: string[] }
export interface XpInsight {
  id: string; kind: 'NARRATIVE' | 'ANOMALY' | 'WHAT_CHANGED' | 'COST' | 'RIGHTSIZING' | 'NEW_PATTERN';
  severity: 'INFO' | 'LOW' | 'MEDIUM' | 'HIGH'; title: string; body: string; accountId: number; instanceId?: string;
  evidence: XpEvidence[]; confidence: 'HIGH' | 'MEDIUM' | 'LOW'; asOf: string;
}
export interface XpForecast { accountId: number; instanceId: string; resource: string; metric: string; daysToFull: number | null; intervalDays: [number, number] | null; r2: number; message: string }
export interface XpTrustPanel {
  collected: string[]; neverCollected: string[]; redaction: { rule: string; example: string }[];
  where: { hotRegion: string; hotDays: number; archiveBucket?: string; archiveRegion?: string; kms?: string };
  access: string[]; controls: string[];
  permissionsDiff: { archiveRole: string[]; connectionRoleNote: string; connectionRoleGrants: string[]; noRunCommand: string };
  templateSha256?: string;
}
export interface XpSavedView { id?: number; name: string; kind: 'LOGS' | 'METRICS' | 'HOSTS'; shared: boolean; state: Record<string, unknown> }
export interface XpAuditEntry { ts: string; userId: number; accountId: number | null; action: string; target?: string; ip?: string }

// ---- setup ----
export interface XpSetupOverview {
  state: XpAccountState; demo: boolean; homeRegion?: string; bucket?: { name: string; region: string; mode: 'CREATE' | 'EXISTING' };
  archive?: { state: 'OFF' | 'OK' | 'FAILING'; failures: number; lastError?: string; watermark?: string };
  regions: { region: string; candidateInstances: number; stackState: 'NONE' | 'DEPLOYING' | 'OK' | 'FAILED'; targetMode: 'INSTANCES' | 'ALL_MANAGED' | 'TAG'; covered: number; reporting: number }[];
  instances: { total: number; ssmManaged: number; inScope: number; enrolled: number; reporting: number; notCovered: number };
  coveragePct: number; lastDataAt: string | null;
}
export interface XpInstanceCandidate {
  instanceId: string; name: string; region: string; platform?: 'LINUX' | 'WINDOWS'; state: string; tags: Record<string, string>;
  ssmManaged: boolean | null; inScope: boolean; collectionLevel: XpCollectionLevel;
  agent: { version?: string; lastSeenAt?: string; status: XpAgentStatus };
}
export interface XpBucketCandidate {
  name: string; region: string; encryption: 'NONE' | 'SSE_S3' | 'SSE_KMS' | 'UNKNOWN'; versioning: boolean | null; publicAccessBlocked: boolean | null;
  lifecycleHasXamPulseRule: boolean | null; recommended: boolean; issues: { code: string; level: 'BLOCK' | 'WARN'; message: string }[];
}
export interface XpRetention { hotDays: number; iaAfterDays: number; glacierIrAfterDays: number; expireAfterDays: number }
export type XpTargetMode = 'INSTANCES' | 'ALL_MANAGED' | 'TAG';
export interface XpSetupPlanRequest {
  bucket: { mode: 'CREATE' | 'EXISTING'; name?: string; region: string };
  kms: { mode: 'CREATE' | 'CUSTOMER_KEY' | 'AWS_MANAGED'; keyArn?: string };
  homeRegion: string;
  regions: { region: string; targetMode: XpTargetMode; instanceIds?: string[]; tagKey?: string; tagValue?: string; confirmAllManaged?: boolean }[];
  retention: XpRetention; sources: { system: boolean; docker: boolean; custom: { path: string; parser?: string }[] };
}
export interface XpSetupPlanResponse {
  planId: string;
  preview: {
    resources: string[]; iamActions: string[]; neverCollected: string[];
    customerSnippets?: { bucketPolicy: string; kmsGrant?: string; lifecycleJson: string };
    customerCostEstimate: { s3Usd: number; kmsUsd: number; notes: string[] };
    connectionRoleGrants: string[]; templateSha256: string; templateUrl: string;
  };
  stacks: XpStackLink[];
  warnings: string[];
}
export interface XpStackStatus { region: string; kind: 'GLOBAL' | 'REGIONAL'; stackName: string; status: string; reason?: string; events: string[]; outputs?: Record<string, string> }
export interface XpChecklistCheck { id: string; region?: string; label: string; state: 'OK' | 'WAIT' | 'FAIL'; hint?: string; docUrl?: string }
export interface XpVerifyResponse { checks: XpChecklistCheck[] }
export interface XpDisconnectRequest { purgeHotData: boolean; confirm: 'DISCONNECT' }
export interface XpDisconnectResponse { jobId: string; stackDeletionLinks: { region: string; url: string }[] }
export interface XpLogSource { id?: number; kind: 'SYSTEM' | 'DOCKER' | 'CUSTOM'; paths: string[]; parser?: string; enabled: boolean; redaction?: { rule: string; pattern: string }[] }
export interface XpDemoStatus { demo: boolean; seededAt?: string; hosts: number; message: string }
export interface XpCollectionUpdate { enabled: boolean; level: XpCollectionLevel; sources?: string[] }
export interface XpRegionPlanRequest { targetMode: XpTargetMode; instanceIds?: string[]; tagKey?: string; tagValue?: string; confirmAllManaged?: boolean }
export interface XpStackLink {
  region: string; kind: 'GLOBAL' | 'REGIONAL'; stackName: string;
  /** Set when the stack does not exist yet (action CREATE). */
  quickCreateUrl?: string;
  action?: 'CREATE' | 'UPDATE';
  /** Set when the stack already exists (action UPDATE): console Update stack flow. */
  updateUrl?: string;
  /** UPDATE only: the parameters to change; '' means clear. Others keep their previous value. */
  parameters?: Record<string, string>;
}

// ---------------------------------------------------------------- EC2 (AWS view, no agent)

export interface XpEc2Volume { volumeId: string; device?: string; type: string; sizeGiB: number; iops?: number; throughputMiBps?: number; encrypted: boolean; deleteOnTermination: boolean }
export interface XpEc2Finding { severity: 'CRITICAL' | 'WARNING' | 'INFO'; title: string; detail: string }
export interface XpEc2ScheduledEvent { code: string; description?: string; notBefore?: string; notAfter?: string }
/** What AWS knows about the instance, from free EC2/Auto Scaling APIs. */
export interface XpEc2Facts {
  instanceId: string; region: string; name: string; state?: string;
  instanceType: string; vcpus?: number; memoryMiB?: number; architecture?: string; platform?: string;
  burstable: boolean; cpuCredits?: string; networkPerformance?: string; ebsBaselineIops?: number; ebsBaselineMbps?: number;
  availabilityZone?: string; vpcId?: string; subnetId?: string; privateIp?: string; publicIp?: string;
  amiId?: string; amiName?: string; amiCreatedAt?: string; launchTime?: string;
  lifecycle: string; autoScalingGroup?: string; autoScalingLifecycleState?: string;
  iamInstanceProfile?: string; securityGroups: string[]; imdsV2Required: boolean; ebsOptimized: boolean; detailedMonitoring: boolean;
  tags: Record<string, string>; volumes: XpEc2Volume[]; status: { system: string; instance: string };
  scheduledEvents: XpEc2ScheduledEvent[]; findings: XpEc2Finding[]; fetchedAt: string;
}
/** CloudWatch data in operator units; source REFRESH = fetched now (billed), STORED = earlier fetches (free). */
export interface XpCloudWatchView {
  instanceId: string; region?: string; from: string; to: string; periodSec: number;
  instance: Record<string, [number, number][]>; volumes: Record<string, Record<string, [number, number][]>>;
  source: 'REFRESH' | 'STORED'; lastFetchedAt?: string; metricsRequested?: number; estimatedCostUsd?: number; note?: string;
}
