/**
 * CloudK8s automation: reason codes, capabilities and the capability model's shapes
 * (docs/cloudk8s/ui-revamp-design.md §6.1, §6.3, §11.1, §11.4).
 *
 * The code lists mirror the backend enum `K8sAutomationReason` and the shared fixture
 * `xamops-service/src/test/resources/k8s/k8s-reason-codes.json`; `src/test/reasonCodes.contract.test.ts`
 * fails when they drift. Codes travel with the `K8S_` prefix in refusal bodies and without it inside
 * payloads (capabilities, engine `gateReasons`); `stripCodePrefix` accepts both.
 */

/** Prefix of every wire code in a refusal body. */
export const CODE_PREFIX = 'K8S_';

/** Gate codes, in the §6.1 table order. A gate disables a control and has copy in `reasonCopy`. */
export const REASON_CODES = [
  'DEMO',
  'PROVIDER_UNSUPPORTED',
  'CONTROLLER_NOT_READY',
  'ANALYSE_ONLY',
  'NO_USER_PERMISSION',
  'READ_ONLY_CONNECTION',
  'CONNECTION_NOT_VERIFIED',
  'AGENT_NOT_INSTALLED',
  'CLUSTER_ACCESS_NOT_CONFIGURED',
  'CLUSTER_UNREACHABLE',
  'NO_METRICS_SOURCE',
  'INSUFFICIENT_HISTORY',
  'GITOPS_MANAGED',
  'MISSING_AWS_PERMISSION',
  'UNKNOWN',
  'GUARDRAIL_LOCKED',
] as const;

export type ReasonCode = (typeof REASON_CODES)[number];

/** Error codes that never disable a control and never appear in capabilities (§6.1). */
export const ERROR_CODES = [
  'UNAUTHENTICATED',
  'INVALID_REQUEST',
  'INVALID_POLICY',
  'NO_ACCOUNT_ACCESS',
  'CLUSTER_NOT_REGISTERED',
  'INTERNAL',
] as const;

export type ErrorCode = (typeof ERROR_CODES)[number];

/** Server-sent detail of `NO_USER_PERMISSION`. */
export const NO_USER_PERMISSION_DETAILS = ['TAB_GRANT', 'ROLE', 'IMPERSONATION'] as const;
export type NoUserPermissionDetail = (typeof NO_USER_PERMISSION_DETAILS)[number];

/** UI-set detail of `AGENT_NOT_INSTALLED`. */
export const AGENT_NOT_INSTALLED_DETAILS = ['AUTOMATION_AGENT', 'XAMOPS_AGENT', 'EXECUTION_PATH'] as const;
export type AgentNotInstalledDetail = (typeof AGENT_NOT_INSTALLED_DETAILS)[number];

export type ReasonDetail = NoUserPermissionDetail | AgentNotInstalledDetail;

export function isReasonCode(value: unknown): value is ReasonCode {
  return typeof value === 'string' && (REASON_CODES as readonly string[]).includes(value);
}

export function isErrorCode(value: unknown): value is ErrorCode {
  return typeof value === 'string' && (ERROR_CODES as readonly string[]).includes(value);
}

export function isNoUserPermissionDetail(value: unknown): value is NoUserPermissionDetail {
  return typeof value === 'string' && (NO_USER_PERMISSION_DETAILS as readonly string[]).includes(value);
}

/** `K8S_READ_ONLY_CONNECTION` and `READ_ONLY_CONNECTION` both give `READ_ONLY_CONNECTION`. */
export function stripCodePrefix(code: string): string {
  return code.startsWith(CODE_PREFIX) ? code.slice(CODE_PREFIX.length) : code;
}

/** One reason a control is blocked (§11.1). */
export interface Gate {
  reason: ReasonCode;
  /** Picks the copy, short label and remedy (§6.1). */
  detail?: ReasonDetail;
  /** Server-provided text (readiness, engine refusals); overrides the reasonCopy text. */
  message?: string;
  /** EXECUTION_PATH's second line: why the optional direct path is missing. */
  directPath?: 'CLUSTER_ACCESS_NOT_CONFIGURED' | 'CLUSTER_UNREACHABLE' | null;
  /** A server code the UI doesn't know: reason is UNKNOWN, rendered as "Not allowed" + message. */
  raw?: string;
}

/** What one control may do now (§11.1). */
export interface Capability {
  /** Commit allowed (Save, a switch flip, a dialog's confirm). */
  allowed: boolean;
  /** Draft edits allowed: true when there is no gate, or DEMO is the only one. */
  editable: boolean;
  /** Primary gate by §6.4 precedence; null when allowed, or while loading (skeleton, no reason line). */
  gate: Gate | null;
  /** Remaining gates in precedence order, one per reason code (§6.4); DEMO last. */
  also: Gate[];
}

/** Every automation control maps to one action id (§6.3). */
export const ACTION_IDS = [
  'vertical.policy.recommend',
  'vertical.policy.auto',
  'vertical.method.persist',
  'vertical.apply',
  'automation.enable',
  'automation.scope',
  'agentRightsizer.edit',
  'evictor.settings',
  'evictor.live',
  'nodeOptimizer.settings',
  'nodeOptimizer.actions',
  'scaleOut.settings',
  'nodeTemplates.edit',
  'rebalance.trigger',
  'rebalance.schedule',
  'guardrails.edit',
  'hpa.apply',
] as const;

export type ActionId = (typeof ACTION_IDS)[number];

export type VerticalActionId = Extract<ActionId, `vertical.${string}`>;

export function isVerticalAction(id: ActionId): id is VerticalActionId {
  return id.startsWith('vertical.');
}

/** Controller readiness keys (§6.7), in property order. */
export const READINESS_KEYS = [
  'vertical-rightsizing',
  'hpa-apply',
  'vpa-apply',
  'evictor-live',
  'rebalancer',
  'node-optimizer-actions',
  'scale-out',
  'node-templates',
] as const;

export type ReadinessKey = (typeof READINESS_KEYS)[number];

// ── GET /api/xamops/k8s/capabilities?accountId= (§11.4) ─────────────────────

export type AccessLevel = 'READ_WRITE' | 'READ_ONLY';
export type AgentStatus = 'CONNECTED' | 'STALE' | 'NOT_INSTALLED' | 'UNKNOWN';
export type NodeAutomationState = 'NOT_INSTALLED' | 'OFF' | 'ANALYSIS' | 'AUTOMATED' | 'UNKNOWN';

export interface CapabilitiesConnection {
  accessLevel: AccessLevel;
  /** The account status, e.g. CONNECTED, PENDING, FAILED. */
  status: string;
}

export interface CapabilitiesWriteGate {
  allowed: boolean;
  /** Every failing write gate, in refusal order, without the prefix. */
  reasons: string[];
}

export interface CapabilitiesReadiness {
  ready: boolean;
  /** CONTROLLER_NOT_READY or ANALYSE_ONLY while not ready, else null. */
  reason: string | null;
  message: string | null;
}

export interface CapabilitiesCluster {
  clusterName: string;
  registered: boolean;
  connection: CapabilitiesConnection;
  writeGate: CapabilitiesWriteGate;
  dataSourceMode: string | null;
  agent: { status: AgentStatus; lastDataAt: string | null; version: string | null };
  automationAgent: { status: AgentStatus; lastSeenAt: string | null; version: string | null };
  nodeAutomation: {
    enabled: boolean;
    mode: string | null;
    evictorAnalyseOnly: boolean | null;
    derivedState: NodeAutomationState;
  };
  dataSources: Array<{ type: string; collectedAt: string | null; items: number | null; displayed: boolean }>;
}

export interface CapabilitiesDto {
  schemaVersion: number;
  accountId: string;
  /** ISO-8601 UTC instant. */
  generatedAt: string;
  connection: CapabilitiesConnection;
  user: {
    canChangeAutomation: boolean;
    /** ["DEMO"], ["NO_USER_PERMISSION"] or []. */
    reasons: string[];
    /** TAB_GRANT, ROLE or IMPERSONATION with NO_USER_PERMISSION, else null. */
    detail: string | null;
  };
  writeGate: CapabilitiesWriteGate;
  /** All eight §6.7 keys. */
  readiness: Record<string, CapabilitiesReadiness>;
  verticalEngine: { enabled: boolean };
  clusters: CapabilitiesCluster[];
}

/** The one refusal body of `/api/eks/autoscaling/**` and the capabilities endpoint (§6.6). */
export interface K8sRefusal {
  success: false;
  /** `K8S_` + reason. */
  code: string;
  message: string;
  field: string | null;
  /** Only with K8S_NO_USER_PERMISSION. */
  detail: string | null;
}

// ── /api/eks/autoscaling/** (Go automation agent), as the backend serves them (§11.4) ────────
// Fields that nothing measures are deliberately absent so they can't be rendered (§10.3):
// kpis.costSavedThisWeek, kpis.nodesSavedToday, rebalancer.lastRebalanceSavingsPercent,
// the rebalance status's savingsPercent, the fleet's estimatedSavingsPct and spot candidates'
// savingsPct / interruptible.

export type AutomationScope = 'full' | 'scoped' | 'disabled';

export interface AutomationScaleOutPolicy {
  enabled: boolean;
  headroomCpuPercent: number;
  headroomMemoryPercent: number;
  minCpuCores: number;
  maxCpuCores: number;
  minRamMib: number;
  maxRamMib: number;
  nodeReadinessTimeoutMinutes: number;
}

export interface AutomationEvictorPolicy {
  enabled: boolean;
  /** Must stay true while `evictor-live` isn't ready (server refuses false). */
  analyseOnly: boolean;
  aggressiveMode: boolean;
  nodeCycleIntervalSeconds: number;
  nodeGracePeriodMinutes: number;
  targetUtilizationCpu: number;
  targetUtilizationMemory: number;
}

export interface AutomationNodeOptimizerPolicy {
  enabled: boolean;
  idleCpuThresholdPct: number;
  idleMemThresholdPct: number;
  autoCordonIdleNodes: boolean;
  autoDrainIdleNodes: boolean;
  handleSpotInterruptions: boolean;
  spotRatioTargetPct: number;
  karpenterEnabled: boolean;
  /** Locked at 1. */
  maxParallelDrains: number;
}

export interface AutomationSafetyGuards {
  respectPodDisruptionBudgets: boolean;
  protectSingleReplicas: boolean;
  protectStatefulSets: boolean;
  protectCriticalNamespaces: boolean;
  /** Omitted or null in a save keeps the stored list. */
  criticalNamespaces: string[] | null;
}

export interface AutomationRebalancerConfig {
  enabled: boolean;
  gracefulDraining: boolean;
  drainTimeoutMinutes: number;
  scheduleEnabled: boolean;
  scheduleCron: string | null;
  /** Server-owned. */
  lastRebalanceAt: string | null;
  /** Server-owned. */
  lastRebalanceDuration: number;
  /** Server-owned; served "idle" while the rebalancer isn't ready. */
  status: string;
}

export interface AutomationNodeTemplate {
  id: string;
  name: string;
  capacityType: string;
  minCpuCores: number;
  maxCpuCores: number;
  minRamMib: number;
  maxRamMib: number;
  architectures: string[] | null;
}

export interface AutomationHpaManagerPolicy {
  enabled: boolean;
  analyseOnly: boolean;
  defaultMinReplicas: number;
  defaultMaxReplicas: number;
  defaultTargetCpuPct: number;
}

export interface AutomationVpaManagerPolicy {
  enabled: boolean;
  analyseOnly: boolean;
  updateMode: string;
}

export interface AutomationEvent {
  id: string;
  timestamp: string;
  type: string;
  severity: string;
  message: string;
  details?: string | null;
  nodeCount?: number | null;
}

export interface AutomationChange {
  id: string;
  /** ISO-8601 UTC. */
  timestamp: string;
  eventType: string;
  message: string;
}

/** `GET /api/eks/autoscaling/{c}`: every section whether or not node automation is on. */
export interface AutomationDashboardDto {
  autoscalingEnabled: boolean;
  policySource: 'stored' | 'default';
  agentPolicyBlockedBy: 'READ_ONLY_CONNECTION' | 'CONNECTION_NOT_VERIFIED' | null;
  agentStatus: { mode: string | null; agentVersion: string | null; lastSyncAt: string | null } | null;
  kpis: {
    pendingPodsResolved: number;
    activeRebalances: number;
    spotRatio: number;
    avgNodeUtilizationCpu: number;
    avgNodeUtilizationMemory: number;
    totalManagedNodes: number;
  } | null;
  scaleOutPolicy: AutomationScaleOutPolicy;
  evictorPolicy: AutomationEvictorPolicy;
  workloadRightsizerPolicy: { enabled: boolean; applyMode: string };
  hpaManagerPolicy: AutomationHpaManagerPolicy;
  vpaManagerPolicy: AutomationVpaManagerPolicy;
  nodeOptimizerPolicy: AutomationNodeOptimizerPolicy;
  nodeTemplates: AutomationNodeTemplate[];
  rebalancer: AutomationRebalancerConfig;
  safetyGuards: AutomationSafetyGuards;
  recentEvents: AutomationEvent[];
  recentChanges: AutomationChange[];
}

export interface AutomationNodeFleet {
  totalNodes: number;
  onDemandNodes: number;
  spotNodes: number;
  idleNodes: Array<{ nodeName: string; cpuUsagePct: number | null; memUsagePct: number | null; reason?: string }>;
  interruptedNodes: string[];
  collectedAt: string | null;
}

export interface AutomationRebalanceStatus {
  /** idle | running | failed | completed | unknown */
  status: string;
  message?: string;
}

export type AutomationEvictorCandidate = Record<string, unknown>;

/** `{ success: true, message }` on every 2xx mutation; the UI refetches instead of trusting it. */
export interface AutomationSaveResult {
  success: boolean;
  message: string;
}
