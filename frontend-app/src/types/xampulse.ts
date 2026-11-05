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
  rightsizing?: { note: string; observedP95Cpu: number; observedP95Mem: number; link: string };
}
export interface XpProcessRow { pid: number; name: string; user?: string; cpuPct: number; memPct: number; memBytes?: number; threads?: number }
export interface XpContainerRow {
  accountId: number; instanceId: string; containerId: string; name: string; image?: string; state?: string;
  cpuPct?: number | null; memBytes?: number | null; restarts?: number | null; lastSeenAt?: string | null;
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
export interface XpLogPattern { patternId: string; template: string; count: number; deltaPct: number | null; isNew: boolean; trend: number[]; firstSeen: string; sampleId: string; severity: XpSeverity }
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
export interface XpAlertPreview { firings: { from: string; to: string; group: Record<string, string>; peak: number }[]; evaluatedPoints: number; wouldNotify: number }
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
  stacks: { region: string; kind: 'GLOBAL' | 'REGIONAL'; stackName: string; quickCreateUrl: string }[];
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
export interface XpStackLink { region: string; kind: 'GLOBAL' | 'REGIONAL'; stackName: string; quickCreateUrl: string }
