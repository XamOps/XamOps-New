/**
 * HPA automation + VPA coexistence (CloudK8s Automation › Pod autoscaler).
 *
 * The HPA page reuses the vertical rightsizer read payload
 * (`GET /api/eks/automation/vertical/{cluster}`): every workload already carries its HPA decision in
 * the `horizontal` block (state / min / max / target / reasons / formula / autoApplyEligible), its
 * native-VPA coexistence target in `container.upstreamVpa`, and the conflict reasons
 * (`VPA_MANAGED` / `VPA_NOT_READABLE`) in the same `horizontal.reasons`. Only the HPA **apply**
 * needs the dedicated endpoint `POST /api/eks/automation/hpa/{cluster}/{namespace}/{name}/apply`
 * (FEAT-002), modelled below.
 *
 * Two layers, as in `verticalRightsizer.ts`:
 *  - the view types the pages render, where a number XamOps did not measure is a dash and an unset
 *    field reads "Not set", never 0;
 *  - the apply request/response wire types, field-for-field with FEAT-002's DTOs.
 *
 * Source of truth: FEAT-001/FEAT-002 findings in
 * .agents/tasks/cloudk8s-hpa-vpa-2026-10-13/features/.
 */

import type { CodeView, VerticalWorkloadRefWire } from './verticalRightsizer';

// ─── HPA decision view (from VerticalWorkloadDto.horizontal) ─────────────────

/** The engine's HPA decision state, as the backend's `HpaDecision.State` names it. */
export type HpaDecisionState = 'NOT_EVALUATED' | 'NO_CHANGE' | 'CHANGE' | string;

/**
 * One HPA's decision, adapted for the page.
 *
 * `minReplicas` / `maxReplicas` / `targetUtilizationPct` are null when the engine leaves that field
 * alone; a value is what an apply would set. `reasons` carry the plain-language HpaReason labels
 * (including the VPA coexistence reasons). `formula` is the human-readable derivation or null.
 */
export interface HpaDecisionView {
  state: HpaDecisionState;
  minReplicas: number | null;
  maxReplicas: number | null;
  targetUtilizationPct: number | null;
  reasons: CodeView[];
  formula: string | null;
  /** Always false this task — HPA has no auto tier — so it is display-only and never gates Apply. */
  autoApplyEligible: boolean;
}

/** The native VPA target XamOps read for one container, for the coexistence comparison. */
export interface NativeVpaTargetView {
  containerName: string;
  cpuRequestM: number | null;
  memRequestMi: number | null;
  source: string | null;
  observedAt: string | null;
}

/**
 * One workload with an HPA (or a D4 recommend-new candidate), as the HPA page renders it.
 *
 * The HPA's live numbers (metric/target, current replicas) are not on the vertical read payload
 * today, so they render as a dash with the honest-empty rule rather than a guess; what the page can
 * always show is the engine's decision, the HPA's identity, and the VPA coexistence state.
 */
export interface HpaWorkloadView {
  /** `namespace/kind/name`, stable enough to key a row. */
  id: string;
  ref: VerticalWorkloadRefWire;
  hpaName: string | null;
  hpaPresent: boolean;
  replicas: number | null;
  gitopsManaged: boolean | null;
  decision: HpaDecisionView;
  /** The native VPA targets beside XamOps's view, one per container that has one. */
  nativeVpa: NativeVpaTargetView[];
  /** The coexistence conflict, resolved from the decision reasons, or null when there is none. */
  vpaCoexistence: VpaCoexistenceView;
}

/**
 * The one coexistence verdict for a workload.
 *
 *  - `managed`: a mutating native VPA owns the pod's requests, so XamOps's vertical engine stands
 *    down (`VPA_MANAGED`) while the HPA may still tune bounds;
 *  - `unreadable`: XamOps could not read the VPA objects (`VPA_NOT_READABLE`), the fail-safe;
 *  - `off`: a native VPA exists but is Mode Off, which changes no pods — not a conflict;
 *  - `none`: no native VPA targets this workload.
 */
export type VpaCoexistenceKind = 'managed' | 'unreadable' | 'off' | 'none';

export interface VpaCoexistenceView {
  kind: VpaCoexistenceKind;
}

/** The engine gating the HPA Apply needs, lifted from the vertical read payload. */
export interface HpaEngineView {
  enabled: boolean;
  ready: boolean;
  /** DIRECT | AGENT | NONE | null. */
  executionPath: string | null;
  /** CONFIGURED | NOT_CONFIGURED | UNREACHABLE | null. */
  clusterAccess: string | null;
  /** The direct-path blocking reason, when the engine gave one. */
  directReason: CodeView | null;
  /** Whether XamOps's own usage source (Prometheus) is reachable. */
  metricsReachable: boolean;
  lastCycleAt: string | null;
  lastCycleEvaluated: number | null;
  lastCycleError: string | null;
}

/** The whole page's view, adapted once from the read payload. */
export interface HpaPageView {
  /** The engine's status and gating. */
  engine: HpaEngineView;
  /** Every workload the engine evaluated, HPA-first ordering applied by the adapter. */
  workloads: HpaWorkloadView[];
  /** Only workloads that have (or could have) an HPA. */
  hpaWorkloads: HpaWorkloadView[];
  /** Workloads a native VPA touches, for the coexistence table. */
  vpaWorkloads: HpaWorkloadView[];
  /** The engine's cluster-level gate reasons, verbatim as codes. */
  gateReasons: CodeView[];
  /** Gate code (no `K8S_` prefix) → the permission rule's detail. */
  gateDetails: Record<string, string>;
  /** Whether the server evaluated this caller's write gates; Apply stays greyed when it did not. */
  writeGatesChecked: boolean;
  /** True when the horizontal engine evaluated anything (state !== NOT_EVALUATED on any workload). */
  horizontalEvaluated: boolean;
  /** Backend contract violations found while adapting (a 0 in a native-VPA resources field). */
  anomalies: string[];
}

export type HpaReadResult =
  | { kind: 'ok'; view: HpaPageView }
  | { kind: 'cluster-not-registered'; message: string }
  | { kind: 'refused'; code: CodeView; message: string }
  | { kind: 'unauthenticated' }
  | { kind: 'error'; message: string };

// ─── Apply (POST /api/eks/automation/hpa/{cluster}/{ns}/{name}/apply) ────────

/**
 * The HPA apply body, field-for-field with FEAT-002's `HpaApplyRequestDto`.
 *
 * A null field means "leave that bound/target alone". `dryRun` is mandatory — the backend answers
 * 400 K8S_INVALID_REQUEST naming `dryRun` when it is absent — and at least one of
 * min/max/target must be non-null. There is no `autoApplyEligible`: HPA has no auto tier.
 */
export interface HpaApplyRequest {
  minReplicas?: number | null;
  maxReplicas?: number | null;
  targetUtilizationPct?: number | null;
  /** Mandatory and echoed back; never defaulted away. */
  dryRun: boolean;
}

export type HpaApplyState = 'ACCEPTED' | 'DUPLICATE' | 'REFUSED' | string;

/** The bounds the patch set (or would set on a dry run), echoed in the response. */
export interface HpaAppliedWire {
  minReplicas: number | null;
  maxReplicas: number | null;
  targetUtilizationPct: number | null;
  dryRun: boolean;
}

export interface HpaApplyResponseWire {
  correlationId?: string | null;
  state?: HpaApplyState | null;
  dryRun?: boolean | null;
  /** Null when refused / expired; present (echoing bounds) when the command applied. */
  applied?: HpaAppliedWire | null;
}

/**
 * The reply to one HPA apply. The direct path completes in-request and returns `applied`; a refusal
 * carries the typed code and (for safety-rule refusals) the HpaReason name as `detail`.
 */
export type HpaApplyResult =
  | {
      kind: 'submitted';
      correlationId: string | null;
      /** null when the server sent none; never defaulted to ACCEPTED. */
      state: HpaApplyState | null;
      dryRun: boolean | null;
      applied: HpaAppliedWire | null;
    }
  /** An untyped 404: this environment runs a build where the HPA write path is not mapped. */
  | { kind: 'writes-not-deployed' }
  /** A typed 404: the endpoint exists, this cluster is not registered here. */
  | { kind: 'cluster-not-registered'; message: string }
  /** `detail` is the HpaReason name on a safety-rule refusal, else the permission rule, else null. */
  | { kind: 'refused'; code: CodeView; message: string; detail: string | null }
  | { kind: 'error'; message: string };

/** The refusal body FEAT-002 reuses from vertical: `{ code, message, field?, detail? }`. */
export interface HpaRefusalWire {
  code?: string | null;
  message?: string | null;
  field?: string | null;
  detail?: string | null;
}

/**
 * A caller-level refusal (demo, permission, read-only connection, account) that holds for every HPA
 * on the page, so it greys every Apply until the next read.
 */
export interface HpaCallerRefusal {
  code: string;
  detail: string | null;
}

/**
 * Whether a user may submit a one-time HPA apply for one workload, and if not, why — the HPA mirror
 * of the vertical `ManualApplyGate`.
 */
export interface HpaApplyGate {
  enabled: boolean;
  /** The text shown beside the greyed control; null exactly when `enabled` is true. */
  reason: string | null;
  /** What the user can do about `reason`, when the glossary gives a next step. */
  nextStep: string | null;
  /** The apply body: the fields the decision would move. Empty when nothing moves. */
  changes: HpaApplyRequest | null;
}
