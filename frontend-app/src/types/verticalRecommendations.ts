/**
 * Vertical rightsizing engine: wire types from the published contract
 * (.agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md §2.1, §2.3, §3.1, §3.2, §4)
 * and the view model components use (docs/cloudk8s/ui-revamp-design.md §11.5, D16).
 *
 * Components and hooks see only the view model; wire details stay in `api/verticalRecommendationsApi.ts`
 * and `api/verticalRecommendationsAdapter.ts`.
 */
import type { ReasonCode } from './k8sAutomation';

// ── Wire (contract) ──────────────────────────────────────────────────────────

/** Integer millicores and MiB; `null` means unset, never 0 (contract §2.3). */
export interface Resources {
  cpuRequestM: number | null;
  memRequestMi: number | null;
  cpuLimitM: number | null;
  memLimitMi: number | null;
}

export interface WorkloadRefWire {
  namespace: string;
  kind: string;
  name: string;
}

export interface VerticalPolicyWire {
  mode: string;
  headroomPct: number;
  percentile: number;
  window: string;
  applyMethod: string;
}

export interface VerticalProvenanceWire {
  percentile: number;
  windowHours: number;
  windowStart: string;
  windowEnd: string;
  /** Fraction 0-1 of the window covered by samples (0.94 = 94%), not a percentage. */
  sampleCoverage: number;
  usageSource: string;
  policySource: string;
}

export interface VerticalContainerWire {
  name: string;
  qosClass: string | null;
  current: Resources;
  /** `null` = no change recommended (always with a reason), never a zero placeholder. */
  recommended: Resources | null;
  reasons: string[];
}

export interface VerticalWorkloadWire {
  ref: WorkloadRefWire;
  policy: VerticalPolicyWire;
  replicas: number | null;
  hpa: { present: boolean; name: string | null } | null;
  gitopsManaged: boolean | null;
  skipReason: string | null;
  findings: string[];
  containers: VerticalContainerWire[];
  lastAction: { at: string; dryRun: boolean; outcome: string } | null;
  /** False for every workload this run; not displayed. */
  autoApplyEligible: boolean;
  provenance?: VerticalProvenanceWire;
  /** The horizontal half; `reasons` carries VPA_MANAGED / VPA_NOT_READABLE when a native VPA owns it. */
  horizontal?: { state: string; reasons?: string[] };
  /**
   * Real $ cost + savings (FEAT-002); null/absent when unavailable or the feature is off. The view
   * model this adapter builds does not surface per-workload cost (SummaryPanel shows only the
   * roll-up), but the field is carried on the wire so the demo fixtures mirror the real response and
   * the parallel `verticalRightsizer` adapter — which does render it — reads the same payload.
   */
  cost?: WorkloadCostWire | null;
}

/** Per-workload cost on the wire (FEAT-002, design §5.1). See `verticalRightsizer.ts` for the rules. */
export interface WorkloadCostWire {
  monthlyCost: number | null;
  monthlySavings: number | null;
  reason: string | null;
  source: string | null;
  capacityType: string | null;
  capacityTypeInferred?: boolean | null;
  note: string | null;
}

/**
 * The cluster cost roll-up (FEAT-002, design §5.1), additive on the status response. `monthlySavings`
 * and `clusterMonthlyCost` are null (with `monthlySavingsUnavailableReason` set) when nothing was
 * priceable, never a fabricated 0. Absent from a build that predates cost / while the feature is off.
 */
export interface CostSummaryWire {
  currency: string;
  hoursPerMonth: number;
  monthlySavings: number | null;
  monthlySavingsUnavailableReason: string | null;
  clusterMonthlyCost: number | null;
  pricedWorkloads: number;
  unpricedWorkloads: number;
}

/** Endpoint 1, `GET /api/eks/automation/vertical/{cluster}` (contract §2.1). */
export interface VerticalStatusWire {
  engine: {
    enabled: boolean;
    ready: boolean;
    /** Direct path only; null = not checked (switch off). Never used for gating. */
    clusterAccess: string | null;
    executionPath: string;
    executionPathDetail: {
      direct: { available: boolean; reason: string | null };
      agent: { available: boolean; reason: string | null; lastSeenAt: string | null };
    };
    metricsSource: { reachable: boolean; name: string | null };
    lastCycle: { at: string | null; evaluated: number | null; error: string | null };
  };
  /** Shared gate codes, no prefix. */
  gateReasons: string[];
  workloads: VerticalWorkloadWire[];
  /** The cluster cost roll-up (FEAT-002); null/absent when unavailable or the feature is off. */
  cost?: CostSummaryWire | null;
}

/** Endpoint 3 body (contract §3.2): per container, a null or omitted field leaves it alone. */
export interface ApplyRequest {
  containers: Array<{ name: string } & Partial<Resources>>;
  applyMethod: string;
  dryRun: boolean;
}

/** Endpoint 3 `202`: a submission, not a result. */
export interface ApplySubmissionWire {
  correlationId: string;
  state: string;
  dryRun: boolean;
}

/** Endpoint 4 (contract §4). */
export interface ActionsPageWire {
  entries: Array<{
    at: string;
    correlationId: string;
    workloadRef: WorkloadRefWire;
    containerChanges: Array<{ name: string; from: Resources; to: Resources; status: string }>;
    method: string;
    dryRun: boolean;
    outcome: string;
    attempt: number;
    triggeredBy: string;
  }>;
  nextBefore: string | null;
}

// ── View model (§11.5) ───────────────────────────────────────────────────────

/** An enum value this UI doesn't know yet; rendered as plain text, never as a known state. */
export type Unknown = { kind: 'unknown'; raw: string };
export type Known<T extends string> = T | Unknown;

export const VERTICAL_MODES = ['OFF', 'RECOMMEND', 'AUTO'] as const;
export const APPLY_METHODS = ['IN_PLACE', 'IN_PLACE_AND_PERSIST'] as const;
export const POLICY_WINDOWS = ['6h', '12h', '24h', '48h', '72h', '168h'] as const;
export const PERCENTILES = ['50', '90', '95', '99'] as const;
export const EXECUTION_PATHS = ['DIRECT', 'AGENT', 'NONE'] as const;
export const DIRECT_PATH_REASONS = ['CLUSTER_ACCESS_NOT_CONFIGURED', 'CLUSTER_UNREACHABLE'] as const;
/** Contract §5.2 — free-form, can grow. */
export const SKIP_REASONS = [
  'PROTECTED_NAMESPACE', 'SINGLE_REPLICA', 'ROLLOUT_IN_PROGRESS', 'LOCKED', 'HPA_VOLATILE',
  'INSUFFICIENT_HISTORY', 'POLICY_OFF',
] as const;
export const FINDINGS = [
  'REQUESTS_UNSET', 'IDLE', 'THROTTLED', 'OOM_RISK', 'HPA_COEXISTENCE', 'NODE_CAPACITY_CAPPED',
] as const;
/** `LIMIT_MOVED` replaced `LIMIT_RAISED` (design-review NIT 1); `OOM_RISK` is also a container reason. */
export const CONTAINER_REASONS = [
  'HPA_CPU_MANAGED', 'HPA_MEMORY_MANAGED', 'FLOOR_CLAMPED', 'BELOW_FLOOR_NO_RAISE', 'LIMIT_MOVED',
  'DEMAND_CENSORED_BY_LIMIT', 'NODE_CAPACITY_CAPPED', 'QOS_PRESERVED_NO_CHANGE', 'COOLDOWN',
  'WITHIN_HYSTERESIS', 'REQUESTS_UNSET', 'OOM_RISK',
] as const;
export const SUBMISSION_STATES = ['ACCEPTED', 'DUPLICATE', 'REFUSED'] as const;
export const ACTION_OUTCOMES = ['APPLIED', 'PARTIALLY_APPLIED', 'REFUSED', 'FAILED', 'EXPIRED'] as const;

export type VerticalMode = (typeof VERTICAL_MODES)[number];
export type ApplyMethod = (typeof APPLY_METHODS)[number];
export type PolicyWindow = (typeof POLICY_WINDOWS)[number];
export type Percentile = (typeof PERCENTILES)[number];
export type ExecutionPath = (typeof EXECUTION_PATHS)[number];
export type DirectPathReason = (typeof DIRECT_PATH_REASONS)[number];
export type SkipReason = (typeof SKIP_REASONS)[number];
export type Finding = (typeof FINDINGS)[number];
export type ContainerReason = (typeof CONTAINER_REASONS)[number];
export type SubmissionState = (typeof SUBMISSION_STATES)[number];
export type ActionOutcome = (typeof ACTION_OUTCOMES)[number];

/** States decided by a `200`; V5, V6 and V8 come from the call itself (§8.1, §11.2). */
export type VerticalStateFromStatus = 'V0' | 'V2' | 'V3' | 'V4' | 'V7';

export interface VerticalContainer {
  name: string;
  qosClass: string | null;
  current: Resources;
  /** `null` = "No change recommended" (contract §2.3). */
  recommended: Resources | null;
  reasons: Array<Known<ContainerReason>>;
  /** A 0 was reported in current or recommended (a contract bug); turned into null. */
  invalidValue: boolean;
  /** The recommendation would change a Guaranteed container's QoS class. */
  qosChange: boolean;
  /** The recommendation is below the safe floors (25m CPU, 250Mi memory). */
  belowFloor: boolean;
}

export interface VerticalWorkload {
  ref: WorkloadRefWire;
  policy: {
    mode: Known<VerticalMode>;
    headroomPct: number | null;
    percentile: Known<Percentile> | null;
    window: Known<PolicyWindow> | null;
    applyMethod: Known<ApplyMethod>;
  };
  replicas: number | null;
  hpa: { present: boolean; name: string | null } | null;
  gitopsManaged: boolean | null;
  skipReason: Known<SkipReason> | null;
  findings: Array<Known<Finding>>;
  containers: VerticalContainer[];
  lastAction: { at: string; dryRun: boolean; outcome: Known<ActionOutcome> } | null;
  provenance: VerticalProvenanceWire | null;
  /**
   * The horizontal half's reason codes (HpaReason names), e.g. `VPA_MANAGED` /
   * `VPA_NOT_READABLE` when a native VPA owns the workload. The engine carries the VPA refusal here
   * rather than on `skipReason`, so the vertical read surfaces must inspect it.
   */
  horizontal?: { reasons: string[] };
  /** Any container flagged. */
  invalidValue: boolean;
  qosChange: boolean;
  belowFloor: boolean;
  /** The engine reports HPA coexistence; the dashboard's HPA check is added on top (FEAT-003). */
  hpaConflict: boolean;
}

/** The cluster cost roll-up in the view (FEAT-002). `null` money fields render as a dash. */
export interface CostSummaryView {
  currency: string;
  hoursPerMonth: number;
  monthlySavings: number | null;
  monthlySavingsUnavailableReason: string | null;
  clusterMonthlyCost: number | null;
  pricedWorkloads: number;
  unpricedWorkloads: number;
}

export interface VerticalEngineView {
  state: VerticalStateFromStatus;
  enabled: boolean;
  ready: boolean;
  executionPath: Known<ExecutionPath>;
  directPath: { available: boolean; reason: Known<DirectPathReason> | null };
  agentPath: { available: boolean; reason: ReasonCode | Unknown | null; lastSeenAt: string | null };
  metricsSource: { reachable: boolean; name: string | null };
  lastCycle: { at: string | null; evaluated: number | null; error: string | null };
  gateReasons: Array<ReasonCode | Unknown>;
  workloads: VerticalWorkload[];
  /** Every listed workload is skipped with POLICY_OFF: "Workloads start Off" (§8.1). */
  allWorkloadsOff: boolean;
  /** The cluster cost roll-up (FEAT-002), or null when the response carried none. */
  cost: CostSummaryView | null;
}

export interface ApplySubmissionView {
  correlationId: string;
  state: Known<SubmissionState>;
  dryRun: boolean;
}

export interface VerticalActionView {
  at: string;
  correlationId: string;
  workloadRef: WorkloadRefWire;
  containerChanges: Array<{ name: string; from: Resources; to: Resources; status: string }>;
  method: Known<ApplyMethod>;
  dryRun: boolean;
  outcome: Known<ActionOutcome>;
  attempt: number;
  triggeredBy: string;
}
