/**
 * Workload vertical rightsizing (CloudK8s Automation tab).
 *
 * Two layers live here on purpose:
 *
 *  - the **wire** types (`*Wire`), which mirror
 *    `GET /api/eks/automation/vertical/{cluster}` field for field, including the contract's rule
 *    that a `null` inside a resources object means **unset** and a `0` is impossible — Kubernetes
 *    cannot express a request of zero, so a `0` on the wire is a backend bug, not "no request";
 *  - the **view** types the Automation tab renders, where every number carries its own provenance
 *    (`value` / `unset` / `bug`) so the UI can never print `0` or a fabricated figure for something
 *    the engine did not measure.
 *
 * Source of truth: .agents/tasks/java-vertical-rightsizer-2026-10-09/api-contract.md §2 and §3.2.
 */

// ─── Wire shape: GET /api/eks/automation/vertical/{cluster} ───────────────────

/** CPU in millicores, memory in MiB. `null` means unset. A `0` is a backend bug. */
export interface VerticalResourcesWire {
  cpuRequestM: number | null;
  memRequestMi: number | null;
  cpuLimitM: number | null;
  memLimitMi: number | null;
}

export interface UpstreamVpaWire {
  cpuRequestM: number | null;
  memRequestMi: number | null;
  source: string | null;
  observedAt: string | null;
}

export interface VerticalContainerWire {
  name: string;
  qosClass: string | null;
  current: VerticalResourcesWire | null;
  /** null when the engine recommends no change — never a copy of `current`. */
  recommended: VerticalResourcesWire | null;
  reasons: string[] | null;
  upstreamVpa: UpstreamVpaWire | null;
}

export interface VerticalWorkloadRefWire {
  namespace: string;
  kind: string;
  name: string;
}

export interface VerticalPolicyWire {
  mode: string | null;
  headroomPct: number | null;
  percentile: number | null;
  window: string | null;
  applyMethod: string | null;
}

export interface VerticalProvenanceWire {
  percentile: number | null;
  windowHours: number | null;
  windowStart: string | null;
  windowEnd: string | null;
  sampleCoverage: number | null;
  usageSource: string | null;
  policySource: string | null;
}

export interface VerticalHorizontalWire {
  state: string | null;
  minReplicas: number | null;
  maxReplicas: number | null;
  targetCpuUtilizationPct: number | null;
  reasons: string[] | null;
}

export interface VerticalWorkloadWire {
  ref: VerticalWorkloadRefWire;
  policy: VerticalPolicyWire | null;
  replicas: number | null;
  hpa: { present: boolean; name: string | null } | null;
  gitopsManaged: boolean | null;
  skipReason: string | null;
  findings: string[] | null;
  containers: VerticalContainerWire[] | null;
  lastAction: { at: string | null; dryRun: boolean | null; outcome: string | null } | null;
  autoApplyEligible: boolean;
  provenance: VerticalProvenanceWire | null;
  horizontal: VerticalHorizontalWire | null;
}

export interface VerticalEngineWire {
  enabled: boolean;
  ready: boolean;
  /** CONFIGURED | NOT_CONFIGURED | UNREACHABLE, or null when the engine has not checked. */
  clusterAccess: string | null;
  /** DIRECT | AGENT | NONE */
  executionPath: string | null;
  executionPathDetail: {
    direct: { available: boolean; reason: string | null } | null;
    agent: { available: boolean; reason: string | null; lastSeenAt: string | null } | null;
  } | null;
  metricsSource: { reachable: boolean; name: string | null } | null;
  lastCycle: { at: string | null; evaluated: number | null; error: string | null } | null;
}

export interface VerticalEngineResponseWire {
  engine: VerticalEngineWire;
  gateReasons: string[] | null;
  workloads: VerticalWorkloadWire[] | null;
}

/** A refusal body: `{ code, message, field? }`. */
export interface VerticalRefusalWire {
  code?: string | null;
  message?: string | null;
  field?: string | null;
}

// ─── View shape ──────────────────────────────────────────────────────────────

/**
 * One number, with where it came from.
 *
 * `unset` renders as `—`. `bug` also renders as `—`, and is additionally reported, because a `0` in
 * a resources field is the exact defect the contract forbids: the reference implementation reported
 * `0m` / `1Mi` as the current state of a container that had neither, then "fixed" its own fabricated
 * baseline for 0% savings.
 */
export type MetricValue =
  | { kind: 'value'; value: number }
  | { kind: 'unset' }
  | { kind: 'bug'; raw: number };

export interface ResourcesView {
  cpuRequestM: MetricValue;
  memRequestMi: MetricValue;
  cpuLimitM: MetricValue;
  memLimitMi: MetricValue;
}

/**
 * A reason, finding, gate or skip code.
 *
 * Unknown values render verbatim, which the contract explicitly allows so the backend's enums can
 * grow without a frontend change.
 */
export type CodeView =
  | { kind: 'known'; code: string; label: string }
  | { kind: 'unknown'; raw: string };

export interface UpstreamVpaView {
  cpuRequestM: MetricValue;
  memRequestMi: MetricValue;
  source: string | null;
  observedAt: string | null;
}

export interface ContainerView {
  name: string;
  qosClass: string | null;
  current: ResourcesView;
  /** null when the engine recommends no change. */
  recommended: ResourcesView | null;
  reasons: CodeView[];
  upstreamVpa: UpstreamVpaView | null;
}

export interface ProvenanceView {
  percentile: number | null;
  windowHours: number | null;
  windowStart: string | null;
  windowEnd: string | null;
  /** 0..1, or null when the engine could not measure the window. */
  sampleCoverage: number | null;
  usageSource: string | null;
  policySource: string | null;
}

export interface WorkloadView {
  /** `namespace/kind/name`, stable enough to key a row. */
  id: string;
  ref: VerticalWorkloadRefWire;
  policy: VerticalPolicyWire | null;
  replicas: number | null;
  hpaName: string | null;
  hpaPresent: boolean;
  gitopsManaged: boolean | null;
  skipReason: CodeView | null;
  findings: CodeView[];
  containers: ContainerView[];
  lastAction: { at: string | null; dryRun: boolean | null; outcome: string | null } | null;
  /**
   * Whether the engine would apply this on its own (policy `AUTO` and every gate). Display only:
   * it is not the condition for a user-initiated apply — see `manualApplyGate`.
   */
  autoApplyEligible: boolean;
  /**
   * Why the engine would not apply this automatically, resolved to the single most useful reason.
   * Not the manual Apply gate.
   */
  applyBlockedBy: CodeView | null;
  provenance: ProvenanceView | null;
  horizontal: VerticalHorizontalWire | null;
}

/**
 * Whether a user may submit a one-time apply for one workload, and if not, why.
 *
 * `reason` is the text shown beside the greyed control; it is null exactly when `enabled` is true.
 * `changes` is the apply body: `recommended` diffed against `current`, so it is empty when nothing
 * would move.
 */
export interface ManualApplyGate {
  enabled: boolean;
  reason: string | null;
  changes: ApplyContainerRequest[];
}

export interface EngineView {
  enabled: boolean;
  ready: boolean;
  clusterAccess: string | null;
  executionPath: string | null;
  directAvailable: boolean;
  directReason: CodeView | null;
  agentAvailable: boolean;
  agentReason: CodeView | null;
  agentLastSeenAt: string | null;
  metricsReachable: boolean;
  metricsName: string | null;
  lastCycleAt: string | null;
  lastCycleEvaluated: number | null;
  lastCycleError: string | null;
}

export interface VerticalEngineView {
  engine: EngineView;
  gateReasons: CodeView[];
  workloads: WorkloadView[];
  /**
   * Backend contract violations found while adapting — currently only a `0` in a resources field.
   * Surfaced in the UI rather than silently rendered as `—`, because it means the payload lied.
   */
  anomalies: string[];
}

// ─── Read result ─────────────────────────────────────────────────────────────

export type VerticalReadResult =
  | { kind: 'ok'; view: VerticalEngineView }
  | { kind: 'cluster-not-registered'; message: string }
  | { kind: 'refused'; code: CodeView; message: string }
  | { kind: 'unauthenticated' }
  | { kind: 'error'; message: string };

// ─── Apply (endpoint 3) ──────────────────────────────────────────────────────

/** Per container, an omitted field means "leave that resource alone". */
export interface ApplyContainerRequest {
  name: string;
  cpuRequestM?: number;
  memRequestMi?: number;
  cpuLimitM?: number;
  memLimitMi?: number;
}

export interface ApplyRequest {
  containers: ApplyContainerRequest[];
  applyMethod: string;
  /** Mandatory in the request and echoed in the response — never defaulted away. */
  dryRun: boolean;
}

export type ApplyState = 'ACCEPTED' | 'DUPLICATE' | 'REFUSED';

/** One container's result, in the backend's rich vocabulary (`APPLIED`, `DEFERRED`, `DRY_RUN`, …). */
export interface ApplyContainerOutcomeWire {
  name: string;
  state: string | null;
  detail: string | null;
  requested: VerticalResourcesWire | null;
  restartExpected: boolean | null;
}

/**
 * One container's before/after on one pod — the restart-free proof in raw numbers.
 *
 * `observedAfter` is read from the pod's `status.containerStatuses[].resources`, which is what is
 * actually in place, not what was asked for.
 */
export interface ApplyContainerProofWire {
  name: string;
  containerIdBefore: string | null;
  containerIdAfter: string | null;
  restartCountBefore: number | null;
  restartCountAfter: number | null;
  observedBefore: VerticalResourcesWire | null;
  observedAfter: VerticalResourcesWire | null;
}

export interface ApplyPodProofWire {
  podName: string;
  uidBefore: string | null;
  uidAfter: string | null;
  samePod: boolean | null;
  ready: boolean | null;
  containers: ApplyContainerProofWire[] | null;
}

/**
 * The additive `result` block on a `202`: the real outcome, or absent when it is not known.
 *
 * `terminal` is richer than the published five audit states on purpose — `DEFERRED`, `INFEASIBLE`,
 * `STUCK_IN_PROGRESS`, `RESTART_DETECTED` and `DRY_RUN` are five different things an operator has to
 * be able to tell apart, and only `APPLIED` may ever be rendered as a success.
 */
export interface ApplyOutcomeWire {
  method: string | null;
  terminal: string | null;
  detail: string | null;
  restartFree: boolean | null;
  containers: ApplyContainerOutcomeWire[] | null;
  pods: ApplyPodProofWire[] | null;
}

/**
 * An apply is a submission first: the contract's shape is asynchronous because the primary execution
 * path is a command queue. `result` is the server's real outcome when it has one, and `null` when it
 * genuinely does not — so the UI never reports success the server did not send.
 */
export type ApplyResult =
  | {
      kind: 'submitted';
      correlationId: string | null;
      state: ApplyState | string;
      dryRun: boolean | null;
      result: ApplyOutcomeWire | null;
    }
  /** An untyped 404: this environment runs a build where the write path is not mapped. */
  | { kind: 'writes-not-deployed' }
  /** A typed 404: the endpoint exists, this cluster is not registered here. A setup step. */
  | { kind: 'cluster-not-registered'; message: string }
  | { kind: 'refused'; code: CodeView; message: string }
  | { kind: 'error'; message: string };
